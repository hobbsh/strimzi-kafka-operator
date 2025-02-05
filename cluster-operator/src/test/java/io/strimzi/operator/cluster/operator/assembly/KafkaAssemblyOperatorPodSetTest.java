/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.cluster.operator.assembly;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.apps.StatefulSet;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.strimzi.api.kafka.StrimziPodSetList;
import io.strimzi.api.kafka.model.Kafka;
import io.strimzi.api.kafka.model.KafkaBuilder;
import io.strimzi.api.kafka.model.StrimziPodSet;
import io.strimzi.api.kafka.model.listener.arraylistener.GenericKafkaListenerBuilder;
import io.strimzi.api.kafka.model.listener.arraylistener.KafkaListenerType;
import io.strimzi.certs.CertManager;
import io.strimzi.operator.KubernetesVersion;
import io.strimzi.operator.PlatformFeaturesAvailability;
import io.strimzi.operator.cluster.ClusterOperatorConfig;
import io.strimzi.operator.cluster.KafkaVersionTestUtils;
import io.strimzi.operator.cluster.ResourceUtils;
import io.strimzi.operator.cluster.model.KafkaCluster;
import io.strimzi.operator.cluster.model.KafkaVersion;
import io.strimzi.operator.cluster.model.ZookeeperCluster;
import io.strimzi.operator.cluster.operator.resource.ResourceOperatorSupplier;
import io.strimzi.operator.cluster.operator.resource.StatefulSetOperator;
import io.strimzi.operator.common.PasswordGenerator;
import io.strimzi.operator.common.Reconciliation;
import io.strimzi.operator.common.operator.MockCertManager;
import io.strimzi.operator.common.operator.resource.CrdOperator;
import io.strimzi.operator.common.operator.resource.PodOperator;
import io.strimzi.operator.common.operator.resource.ReconcileResult;
import io.strimzi.operator.common.operator.resource.SecretOperator;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.Checkpoint;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;

import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.function.Function;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(VertxExtension.class)
public class KafkaAssemblyOperatorPodSetTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final KafkaVersion.Lookup VERSIONS = KafkaVersionTestUtils.getKafkaVersionLookup();
    private static final KubernetesVersion KUBERNETES_VERSION = KubernetesVersion.V1_18;
    private static final MockCertManager CERT_MANAGER = new MockCertManager();
    private static final PasswordGenerator PASSWORD_GENERATOR = new PasswordGenerator(10, "a", "a");
    private static final String NAMESPACE = "my-ns";
    private static final String CLUSTER_NAME = "my-cluster";
    private static final Kafka KAFKA = new KafkaBuilder()
                .withNewMetadata()
                    .withName(CLUSTER_NAME)
                    .withNamespace(NAMESPACE)
                .endMetadata()
                .withNewSpec()
                    .withNewKafka()
                        .withReplicas(3)
                        .withListeners(new GenericKafkaListenerBuilder()
                                .withName("plain")
                                .withPort(9092)
                                .withType(KafkaListenerType.INTERNAL)
                                .withTls(false)
                                .build())
                        .withNewEphemeralStorage()
                        .endEphemeralStorage()
                    .endKafka()
                    .withNewZookeeper()
                        .withReplicas(3)
                        .withNewEphemeralStorage()
                        .endEphemeralStorage()
                    .endZookeeper()
                .endSpec()
                .build();

    protected static Vertx vertx;

    @BeforeAll
    public static void beforeAll() {
        vertx = Vertx.vertx();
    }

    @AfterAll
    public static void afterAll() {
        vertx.close();
    }

    /**
     * Tests the regular reconciliation of the Kafka cluster when the UseStrimziPodsSet is already enabled for some time
     *
     * @param context   Test context
     */
    @Test
    public void testRegularReconciliation(VertxTestContext context)  {
        ZookeeperCluster zkCluster = ZookeeperCluster.fromCrd(Reconciliation.DUMMY_RECONCILIATION, KAFKA, VERSIONS);
        StrimziPodSet zkPodSet = zkCluster.generatePodSet(KAFKA.getSpec().getZookeeper().getReplicas(), false, null, null, null);
        KafkaCluster kafkaCluster = KafkaCluster.fromCrd(Reconciliation.DUMMY_RECONCILIATION, KAFKA, VERSIONS);

        ResourceOperatorSupplier supplier = ResourceUtils.supplierWithMocks(false);

        SecretOperator secretOps = supplier.secretOperations;
        when(secretOps.reconcile(any(), any(), any(), any())).thenReturn(Future.succeededFuture());

        CrdOperator<KubernetesClient, StrimziPodSet, StrimziPodSetList> mockPodSetOps = supplier.strimziPodSetOperator;
        when(mockPodSetOps.getAsync(any(), eq(zkCluster.getName()))).thenReturn(Future.succeededFuture(zkPodSet));
        when(mockPodSetOps.reconcile(any(), any(), eq(zkCluster.getName()), any())).thenReturn(Future.succeededFuture(ReconcileResult.noop(zkPodSet)));

        StatefulSetOperator mockStsOps = supplier.stsOperations;
        when(mockStsOps.getAsync(any(), eq(zkCluster.getName()))).thenReturn(Future.succeededFuture(null)); // Zoo STS is queried and deleted if it still exists
        when(mockStsOps.getAsync(any(), eq(kafkaCluster.getName()))).thenReturn(Future.succeededFuture(kafkaCluster.generateStatefulSet(false, null, null)));

        PodOperator mockPodOps = supplier.podOperations;
        when(mockPodOps.listAsync(any(), eq(kafkaCluster.getSelectorLabels()))).thenReturn(Future.succeededFuture(Collections.emptyList()));

        CrdOperator mockKafkaOps = supplier.kafkaOperator;
        when(mockKafkaOps.getAsync(eq(NAMESPACE), eq(CLUSTER_NAME))).thenReturn(Future.succeededFuture(KAFKA));
        when(mockKafkaOps.get(eq(NAMESPACE), eq(CLUSTER_NAME))).thenReturn(KAFKA);
        when(mockKafkaOps.updateStatusAsync(any(), any())).thenReturn(Future.succeededFuture());

        ClusterOperatorConfig config = ResourceUtils.dummyClusterOperatorConfig(VERSIONS, ClusterOperatorConfig.DEFAULT_OPERATION_TIMEOUT_MS, "+UseStrimziPodSets");

        MockKafkaAssemblyOperator kao = new MockKafkaAssemblyOperator(
                vertx, new PlatformFeaturesAvailability(false, KUBERNETES_VERSION),
                CERT_MANAGER,
                PASSWORD_GENERATOR,
                supplier,
                config);

        Checkpoint async = context.checkpoint();
        kao.reconcile(new Reconciliation("test-trigger", Kafka.RESOURCE_KIND, NAMESPACE, CLUSTER_NAME))
                .onComplete(context.succeeding(v -> context.verify(() -> {
                    assertThat(kao.maybeRollZooKeeperInvocations, is(1));
                    assertThat(kao.zooPodNeedsRestart.apply(podFromPodSet(zkPodSet, "my-cluster-zookeeper-0")), is(List.of()));
                    assertThat(kao.zooPodNeedsRestart.apply(podFromPodSet(zkPodSet, "my-cluster-zookeeper-1")), is(List.of()));
                    assertThat(kao.zooPodNeedsRestart.apply(podFromPodSet(zkPodSet, "my-cluster-zookeeper-2")), is(List.of()));

                    async.flag();
                })));
    }

    /**
     * Tests the first reconciliation of the Kafka cluster after the UseStrimziPodsSet is enabled for the first time
     *
     * @param context   Test context
     */
    @Test
    public void testFirstReconciliation(VertxTestContext context)  {
        ZookeeperCluster zkCluster = ZookeeperCluster.fromCrd(Reconciliation.DUMMY_RECONCILIATION, KAFKA, VERSIONS);
        StrimziPodSet zkPodSet = zkCluster.generatePodSet(KAFKA.getSpec().getZookeeper().getReplicas(), false, null, null, null);
        KafkaCluster kafkaCluster = KafkaCluster.fromCrd(Reconciliation.DUMMY_RECONCILIATION, KAFKA, VERSIONS);

        ResourceOperatorSupplier supplier = ResourceUtils.supplierWithMocks(false);

        SecretOperator secretOps = supplier.secretOperations;
        when(secretOps.reconcile(any(), any(), any(), any())).thenReturn(Future.succeededFuture());

        CrdOperator<KubernetesClient, StrimziPodSet, StrimziPodSetList> mockPodSetOps = supplier.strimziPodSetOperator;
        when(mockPodSetOps.getAsync(any(), eq(zkCluster.getName()))).thenReturn(Future.succeededFuture(null)); // The PodSet does not exist yet in the first reconciliation
        when(mockPodSetOps.reconcile(any(), any(), eq(zkCluster.getName()), any())).thenReturn(Future.succeededFuture(ReconcileResult.created(zkPodSet)));

        StatefulSetOperator mockStsOps = supplier.stsOperations;
        when(mockStsOps.getAsync(any(), eq(zkCluster.getName()))).thenReturn(Future.succeededFuture(zkCluster.generateStatefulSet(false, null, null))); // Zoo STS still exists in the first reconciliation
        when(mockStsOps.deleteAsync(any(), any(), eq(zkCluster.getName()), eq(false))).thenReturn(Future.succeededFuture()); // The Zoo STS will be deleted during the reconciliation
        when(mockStsOps.getAsync(any(), eq(kafkaCluster.getName()))).thenReturn(Future.succeededFuture(kafkaCluster.generateStatefulSet(false, null, null)));

        PodOperator mockPodOps = supplier.podOperations;
        when(mockPodOps.listAsync(any(), eq(kafkaCluster.getSelectorLabels()))).thenReturn(Future.succeededFuture(Collections.emptyList()));

        CrdOperator mockKafkaOps = supplier.kafkaOperator;
        when(mockKafkaOps.getAsync(eq(NAMESPACE), eq(CLUSTER_NAME))).thenReturn(Future.succeededFuture(KAFKA));
        when(mockKafkaOps.get(eq(NAMESPACE), eq(CLUSTER_NAME))).thenReturn(KAFKA);
        when(mockKafkaOps.updateStatusAsync(any(), any())).thenReturn(Future.succeededFuture());

        ClusterOperatorConfig config = ResourceUtils.dummyClusterOperatorConfig(VERSIONS, ClusterOperatorConfig.DEFAULT_OPERATION_TIMEOUT_MS, "+UseStrimziPodSets");

        MockKafkaAssemblyOperator kao = new MockKafkaAssemblyOperator(
                vertx, new PlatformFeaturesAvailability(false, KUBERNETES_VERSION),
                CERT_MANAGER,
                PASSWORD_GENERATOR,
                supplier,
                config);

        Checkpoint async = context.checkpoint();
        kao.reconcile(new Reconciliation("test-trigger", Kafka.RESOURCE_KIND, NAMESPACE, CLUSTER_NAME))
                .onComplete(context.succeeding(v -> context.verify(() -> {
                    // Test that the old Zoo STS was deleted
                    verify(mockStsOps, times(1)).deleteAsync(any(), any(), eq(zkCluster.getName()), eq(false));

                    assertThat(kao.maybeRollZooKeeperInvocations, is(1));
                    assertThat(kao.zooPodNeedsRestart.apply(podFromPodSet(zkPodSet, "my-cluster-zookeeper-0")), is(List.of()));
                    assertThat(kao.zooPodNeedsRestart.apply(podFromPodSet(zkPodSet, "my-cluster-zookeeper-1")), is(List.of()));
                    assertThat(kao.zooPodNeedsRestart.apply(podFromPodSet(zkPodSet, "my-cluster-zookeeper-2")), is(List.of()));

                    async.flag();
                })));
    }

    /**
     * Tests the first reconciliation of the Kafka cluster after the UseStrimziPodsSet is disabled for the first time
     *
     * @param context   Test context
     */
    @Test
    public void testFirstReconciliationWithSts(VertxTestContext context)  {
        ZookeeperCluster zkCluster = ZookeeperCluster.fromCrd(Reconciliation.DUMMY_RECONCILIATION, KAFKA, VERSIONS);
        StrimziPodSet zkPodSet = zkCluster.generatePodSet(KAFKA.getSpec().getZookeeper().getReplicas(), false, null, null, null);
        KafkaCluster kafkaCluster = KafkaCluster.fromCrd(Reconciliation.DUMMY_RECONCILIATION, KAFKA, VERSIONS);

        ResourceOperatorSupplier supplier = ResourceUtils.supplierWithMocks(false);

        SecretOperator secretOps = supplier.secretOperations;
        when(secretOps.reconcile(any(), any(), any(), any())).thenReturn(Future.succeededFuture());

        CrdOperator<KubernetesClient, StrimziPodSet, StrimziPodSetList> mockPodSetOps = supplier.strimziPodSetOperator;
        when(mockPodSetOps.getAsync(any(), eq(zkCluster.getName()))).thenReturn(Future.succeededFuture(zkPodSet)); // The PodSet still exists and should be deleted int he first reconciliation
        when(mockPodSetOps.deleteAsync(any(), any(), eq(zkCluster.getName()), eq(false))).thenReturn(Future.succeededFuture()); // The Zoo PodSet will be deleted during the reconciliation

        StatefulSetOperator mockStsOps = supplier.stsOperations;
        when(mockStsOps.getAsync(any(), eq(zkCluster.getName()))).thenReturn(Future.succeededFuture(null)); // Zoo STS does not exist yet
        when(mockStsOps.reconcile(any(), any(), eq(zkCluster.getName()), any())).thenAnswer(i -> Future.succeededFuture(ReconcileResult.created(i.getArgument(3))));
        when(mockStsOps.getAsync(any(), eq(kafkaCluster.getName()))).thenReturn(Future.succeededFuture(kafkaCluster.generateStatefulSet(false, null, null)));

        PodOperator mockPodOps = supplier.podOperations;
        when(mockPodOps.listAsync(any(), eq(kafkaCluster.getSelectorLabels()))).thenReturn(Future.succeededFuture(Collections.emptyList()));

        CrdOperator mockKafkaOps = supplier.kafkaOperator;
        when(mockKafkaOps.getAsync(eq(NAMESPACE), eq(CLUSTER_NAME))).thenReturn(Future.succeededFuture(KAFKA));
        when(mockKafkaOps.get(eq(NAMESPACE), eq(CLUSTER_NAME))).thenReturn(KAFKA);
        when(mockKafkaOps.updateStatusAsync(any(), any())).thenReturn(Future.succeededFuture());

        ClusterOperatorConfig config = ResourceUtils.dummyClusterOperatorConfig(VERSIONS, ClusterOperatorConfig.DEFAULT_OPERATION_TIMEOUT_MS, "-UseStrimziPodSets");

        MockKafkaAssemblyOperator kao = new MockKafkaAssemblyOperator(
                vertx, new PlatformFeaturesAvailability(false, KUBERNETES_VERSION),
                CERT_MANAGER,
                PASSWORD_GENERATOR,
                supplier,
                config);

        Checkpoint async = context.checkpoint();
        kao.reconcile(new Reconciliation("test-trigger", Kafka.RESOURCE_KIND, NAMESPACE, CLUSTER_NAME))
                .onComplete(context.succeeding(v -> context.verify(() -> {
                    // Test that the old Zoo Pod Set was deleted
                    verify(mockPodSetOps, times(1)).deleteAsync(any(), any(), eq(zkCluster.getName()), eq(false));

                    assertThat(kao.maybeRollZooKeeperInvocations, is(1));
                    assertThat(kao.zooPodNeedsRestart.apply(podFromPodSet(zkPodSet, "my-cluster-zookeeper-0")), is(List.of()));
                    assertThat(kao.zooPodNeedsRestart.apply(podFromPodSet(zkPodSet, "my-cluster-zookeeper-1")), is(List.of()));
                    assertThat(kao.zooPodNeedsRestart.apply(podFromPodSet(zkPodSet, "my-cluster-zookeeper-2")), is(List.of()));

                    async.flag();
                })));
    }

    /**
     * Tests the regular reconciliation of the Kafka cluster which results in some rolling updates
     *
     * @param context   Test context
     */
    @Test
    public void testReconciliationWithRoll(VertxTestContext context)  {
        Kafka oldKafka = new KafkaBuilder(KAFKA)
                .editSpec()
                    .editZookeeper()
                        .withImage("old-image:latest")
                    .endZookeeper()
                .endSpec()
                .build();

        ZookeeperCluster oldZkCluster = ZookeeperCluster.fromCrd(Reconciliation.DUMMY_RECONCILIATION, oldKafka, VERSIONS);
        StrimziPodSet oldZkPodSet = oldZkCluster.generatePodSet(KAFKA.getSpec().getZookeeper().getReplicas(), false, null, null, null);

        ZookeeperCluster newZkCluster = ZookeeperCluster.fromCrd(Reconciliation.DUMMY_RECONCILIATION, KAFKA, VERSIONS);
        KafkaCluster kafkaCluster = KafkaCluster.fromCrd(Reconciliation.DUMMY_RECONCILIATION, KAFKA, VERSIONS);

        ResourceOperatorSupplier supplier = ResourceUtils.supplierWithMocks(false);

        SecretOperator secretOps = supplier.secretOperations;
        when(secretOps.reconcile(any(), any(), any(), any())).thenReturn(Future.succeededFuture());

        CrdOperator<KubernetesClient, StrimziPodSet, StrimziPodSetList> mockPodSetOps = supplier.strimziPodSetOperator;
        when(mockPodSetOps.getAsync(any(), eq(newZkCluster.getName()))).thenReturn(Future.succeededFuture(oldZkPodSet));
        when(mockPodSetOps.reconcile(any(), any(), eq(newZkCluster.getName()), any())).thenAnswer(i -> Future.succeededFuture(ReconcileResult.noop(i.getArgument(3))));

        StatefulSetOperator mockStsOps = supplier.stsOperations;
        when(mockStsOps.getAsync(any(), eq(newZkCluster.getName()))).thenReturn(Future.succeededFuture(null)); // Zoo STS is queried and deleted if it still exists
        when(mockStsOps.getAsync(any(), eq(kafkaCluster.getName()))).thenReturn(Future.succeededFuture(kafkaCluster.generateStatefulSet(false, null, null)));

        PodOperator mockPodOps = supplier.podOperations;
        when(mockPodOps.listAsync(any(), eq(kafkaCluster.getSelectorLabels()))).thenReturn(Future.succeededFuture(Collections.emptyList()));

        CrdOperator mockKafkaOps = supplier.kafkaOperator;
        when(mockKafkaOps.getAsync(eq(NAMESPACE), eq(CLUSTER_NAME))).thenReturn(Future.succeededFuture(KAFKA));
        when(mockKafkaOps.get(eq(NAMESPACE), eq(CLUSTER_NAME))).thenReturn(KAFKA);
        when(mockKafkaOps.updateStatusAsync(any(), any())).thenReturn(Future.succeededFuture());

        ClusterOperatorConfig config = ResourceUtils.dummyClusterOperatorConfig(VERSIONS, ClusterOperatorConfig.DEFAULT_OPERATION_TIMEOUT_MS, "+UseStrimziPodSets");

        MockKafkaAssemblyOperator kao = new MockKafkaAssemblyOperator(
                vertx, new PlatformFeaturesAvailability(false, KUBERNETES_VERSION),
                CERT_MANAGER,
                PASSWORD_GENERATOR,
                supplier,
                config);

        Checkpoint async = context.checkpoint();
        kao.reconcile(new Reconciliation("test-trigger", Kafka.RESOURCE_KIND, NAMESPACE, CLUSTER_NAME))
                .onComplete(context.succeeding(v -> context.verify(() -> {
                    assertThat(kao.maybeRollZooKeeperInvocations, is(1));
                    assertThat(kao.zooPodNeedsRestart.apply(podFromPodSet(oldZkPodSet, "my-cluster-zookeeper-0")), is(List.of("Pod has old revision")));
                    assertThat(kao.zooPodNeedsRestart.apply(podFromPodSet(oldZkPodSet, "my-cluster-zookeeper-1")), is(List.of("Pod has old revision")));
                    assertThat(kao.zooPodNeedsRestart.apply(podFromPodSet(oldZkPodSet, "my-cluster-zookeeper-2")), is(List.of("Pod has old revision")));

                    async.flag();
                })));
    }

    /**
     * Tests reconciliation with scale-up from 1 to 3 ZooKeeper pods
     *
     * @param context   Test context
     */
    @Test
    public void testScaleUp(VertxTestContext context)  {
        Kafka oldKafka = new KafkaBuilder(KAFKA)
                .editSpec()
                    .editZookeeper()
                        .withReplicas(1)
                    .endZookeeper()
                .endSpec()
                .build();

        ZookeeperCluster oldZkCluster = ZookeeperCluster.fromCrd(Reconciliation.DUMMY_RECONCILIATION, oldKafka, VERSIONS);
        StrimziPodSet oldZkPodSet = oldZkCluster.generatePodSet(oldKafka.getSpec().getZookeeper().getReplicas(), false, null, null, null);

        ZookeeperCluster zkCluster = ZookeeperCluster.fromCrd(Reconciliation.DUMMY_RECONCILIATION, KAFKA, VERSIONS);
        KafkaCluster kafkaCluster = KafkaCluster.fromCrd(Reconciliation.DUMMY_RECONCILIATION, KAFKA, VERSIONS);

        ResourceOperatorSupplier supplier = ResourceUtils.supplierWithMocks(false);

        SecretOperator secretOps = supplier.secretOperations;
        when(secretOps.reconcile(any(), any(), any(), any())).thenReturn(Future.succeededFuture());
        when(secretOps.getAsync(any(), any())).thenReturn(Future.succeededFuture(new Secret()));

        CrdOperator<KubernetesClient, StrimziPodSet, StrimziPodSetList> mockPodSetOps = supplier.strimziPodSetOperator;
        when(mockPodSetOps.getAsync(any(), eq(zkCluster.getName()))).thenReturn(Future.succeededFuture(oldZkPodSet));
        ArgumentCaptor<StrimziPodSet> zkPodSetCaptor =  ArgumentCaptor.forClass(StrimziPodSet.class);
        when(mockPodSetOps.reconcile(any(), any(), eq(zkCluster.getName()), zkPodSetCaptor.capture())).thenAnswer(i -> Future.succeededFuture(ReconcileResult.noop(i.getArgument(3))));

        StatefulSetOperator mockStsOps = supplier.stsOperations;
        when(mockStsOps.getAsync(any(), eq(zkCluster.getName()))).thenReturn(Future.succeededFuture(null)); // Zoo STS is queried and deleted if it still exists
        when(mockStsOps.getAsync(any(), eq(kafkaCluster.getName()))).thenReturn(Future.succeededFuture(kafkaCluster.generateStatefulSet(false, null, null)));

        PodOperator mockPodOps = supplier.podOperations;
        when(mockPodOps.listAsync(any(), eq(kafkaCluster.getSelectorLabels()))).thenReturn(Future.succeededFuture(Collections.emptyList()));
        when(mockPodOps.readiness(any(), any(), any(), anyLong(), anyLong())).thenReturn(Future.succeededFuture());

        CrdOperator mockKafkaOps = supplier.kafkaOperator;
        when(mockKafkaOps.getAsync(eq(NAMESPACE), eq(CLUSTER_NAME))).thenReturn(Future.succeededFuture(KAFKA));
        when(mockKafkaOps.get(eq(NAMESPACE), eq(CLUSTER_NAME))).thenReturn(KAFKA);
        when(mockKafkaOps.updateStatusAsync(any(), any())).thenReturn(Future.succeededFuture());

        ClusterOperatorConfig config = ResourceUtils.dummyClusterOperatorConfig(VERSIONS, ClusterOperatorConfig.DEFAULT_OPERATION_TIMEOUT_MS, "+UseStrimziPodSets");

        MockKafkaAssemblyOperator kao = new MockKafkaAssemblyOperator(
                vertx, new PlatformFeaturesAvailability(false, KUBERNETES_VERSION),
                CERT_MANAGER,
                PASSWORD_GENERATOR,
                supplier,
                config);

        Checkpoint async = context.checkpoint();
        kao.reconcile(new Reconciliation("test-trigger", Kafka.RESOURCE_KIND, NAMESPACE, CLUSTER_NAME))
                .onComplete(context.succeeding(v -> context.verify(() -> {
                    // Scale-up is done pod by pod => the reconcile method is called 3 times with 1, 2 and 3 pods.
                    assertThat(zkPodSetCaptor.getAllValues().size(), is(3));
                    assertThat(zkPodSetCaptor.getAllValues().get(0).getSpec().getPods().size(), is(1));
                    assertThat(zkPodSetCaptor.getAllValues().get(1).getSpec().getPods().size(), is(2));
                    assertThat(zkPodSetCaptor.getAllValues().get(2).getSpec().getPods().size(), is(3));

                    // Still one maybe-roll invocation
                    assertThat(kao.maybeRollZooKeeperInvocations, is(1));

                    async.flag();
                })));
    }

    /**
     * Tests reconciliation with scale-down from 5 to 3 ZooKeeper pods
     *
     * @param context   Test context
     */
    @Test
    public void testScaleDown(VertxTestContext context)  {
        Kafka oldKafka = new KafkaBuilder(KAFKA)
                .editSpec()
                    .editZookeeper()
                        .withReplicas(5)
                    .endZookeeper()
                .endSpec()
                .build();

        ZookeeperCluster oldZkCluster = ZookeeperCluster.fromCrd(Reconciliation.DUMMY_RECONCILIATION, oldKafka, VERSIONS);
        StrimziPodSet oldZkPodSet = oldZkCluster.generatePodSet(oldKafka.getSpec().getZookeeper().getReplicas(), false, null, null, null);

        ZookeeperCluster zkCluster = ZookeeperCluster.fromCrd(Reconciliation.DUMMY_RECONCILIATION, KAFKA, VERSIONS);
        KafkaCluster kafkaCluster = KafkaCluster.fromCrd(Reconciliation.DUMMY_RECONCILIATION, KAFKA, VERSIONS);

        ResourceOperatorSupplier supplier = ResourceUtils.supplierWithMocks(false);

        SecretOperator secretOps = supplier.secretOperations;
        when(secretOps.reconcile(any(), any(), any(), any())).thenReturn(Future.succeededFuture());
        when(secretOps.getAsync(any(), any())).thenReturn(Future.succeededFuture(new Secret()));

        CrdOperator<KubernetesClient, StrimziPodSet, StrimziPodSetList> mockPodSetOps = supplier.strimziPodSetOperator;
        when(mockPodSetOps.getAsync(any(), eq(zkCluster.getName()))).thenReturn(Future.succeededFuture(oldZkPodSet));
        ArgumentCaptor<StrimziPodSet> zkPodSetCaptor =  ArgumentCaptor.forClass(StrimziPodSet.class);
        when(mockPodSetOps.reconcile(any(), any(), eq(zkCluster.getName()), zkPodSetCaptor.capture())).thenAnswer(i -> Future.succeededFuture(ReconcileResult.noop(i.getArgument(3))));

        StatefulSetOperator mockStsOps = supplier.stsOperations;
        when(mockStsOps.getAsync(any(), eq(zkCluster.getName()))).thenReturn(Future.succeededFuture(null)); // Zoo STS is queried and deleted if it still exists
        when(mockStsOps.getAsync(any(), eq(kafkaCluster.getName()))).thenReturn(Future.succeededFuture(kafkaCluster.generateStatefulSet(false, null, null)));

        PodOperator mockPodOps = supplier.podOperations;
        when(mockPodOps.listAsync(any(), eq(kafkaCluster.getSelectorLabels()))).thenReturn(Future.succeededFuture(Collections.emptyList()));
        when(mockPodOps.readiness(any(), any(), any(), anyLong(), anyLong())).thenReturn(Future.succeededFuture());

        CrdOperator mockKafkaOps = supplier.kafkaOperator;
        when(mockKafkaOps.getAsync(eq(NAMESPACE), eq(CLUSTER_NAME))).thenReturn(Future.succeededFuture(KAFKA));
        when(mockKafkaOps.get(eq(NAMESPACE), eq(CLUSTER_NAME))).thenReturn(KAFKA);
        when(mockKafkaOps.updateStatusAsync(any(), any())).thenReturn(Future.succeededFuture());

        ClusterOperatorConfig config = ResourceUtils.dummyClusterOperatorConfig(VERSIONS, ClusterOperatorConfig.DEFAULT_OPERATION_TIMEOUT_MS, "+UseStrimziPodSets");

        MockKafkaAssemblyOperator kao = new MockKafkaAssemblyOperator(
                vertx, new PlatformFeaturesAvailability(false, KUBERNETES_VERSION),
                CERT_MANAGER,
                PASSWORD_GENERATOR,
                supplier,
                config);

        Checkpoint async = context.checkpoint();
        kao.reconcile(new Reconciliation("test-trigger", Kafka.RESOURCE_KIND, NAMESPACE, CLUSTER_NAME))
                .onComplete(context.succeeding(v -> context.verify(() -> {
                    // Scale-up is done pod by pod => the reconcile method is called 3 times with 1, 2 and 3 pods.
                    assertThat(zkPodSetCaptor.getAllValues().size(), is(3));
                    assertThat(zkPodSetCaptor.getAllValues().get(0).getSpec().getPods().size(), is(5));
                    assertThat(zkPodSetCaptor.getAllValues().get(1).getSpec().getPods().size(), is(4));
                    assertThat(zkPodSetCaptor.getAllValues().get(2).getSpec().getPods().size(), is(3));

                    // Still one maybe-roll invocation
                    assertThat(kao.maybeRollZooKeeperInvocations, is(1));

                    async.flag();
                })));
    }

    // Internal utility methods
    private Pod podFromPodSet(StrimziPodSet podSet, String name) {
        return podSet.getSpec().getPods().stream().map(p -> MAPPER.convertValue(p, Pod.class)).filter(p -> name.equals(p.getMetadata().getName())).findFirst().orElse(null);
    }

    class MockKafkaAssemblyOperator extends KafkaAssemblyOperator  {
        int maybeRollKafkaInvocations = 0;
        Function<Pod, List<String>> kafkaPodNeedsRestart = null;

        int maybeRollZooKeeperInvocations = 0;
        Function<Pod, List<String>> zooPodNeedsRestart = null;

        public MockKafkaAssemblyOperator(Vertx vertx, PlatformFeaturesAvailability pfa, CertManager certManager, PasswordGenerator passwordGenerator, ResourceOperatorSupplier supplier, ClusterOperatorConfig config) {
            super(vertx, pfa, certManager, passwordGenerator, supplier, config);
        }

        ReconciliationState createReconciliationState(Reconciliation reconciliation, Kafka kafkaAssembly) {
            return new MockReconciliationState(reconciliation, kafkaAssembly);
        }

        @Override
        Future<Void> reconcile(ReconciliationState reconcileState)  {
            return Future.succeededFuture(reconcileState)
                    .compose(state -> state.reconcileCas(() -> new Date()))
                    .compose(state -> state.getKafkaClusterDescription())
                    .compose(state -> state.getZookeeperDescription())
                    .compose(state -> state.zkStatefulSet())
                    .compose(state -> state.zkPodSet())
                    .compose(state -> state.zkScalingDown())
                    .compose(state -> state.zkRollingUpdate())
                    .compose(state -> state.zkScalingUp())
                    .mapEmpty();
        }

        class MockReconciliationState extends ReconciliationState {
            MockReconciliationState(Reconciliation reconciliation, Kafka kafkaAssembly) {
                super(reconciliation, kafkaAssembly);
            }

            Future<Void> maybeRollZooKeeper(Function<Pod, List<String>> podNeedsRestart) {
                maybeRollZooKeeperInvocations++;
                zooPodNeedsRestart = podNeedsRestart;
                return Future.succeededFuture();
            }

            Future<Void> maybeRollKafka(StatefulSet sts, Function<Pod, List<String>> podNeedsRestart) {
                maybeRollKafkaInvocations++;
                kafkaPodNeedsRestart = podNeedsRestart;
                return Future.succeededFuture();
            }
        }
    }
}
