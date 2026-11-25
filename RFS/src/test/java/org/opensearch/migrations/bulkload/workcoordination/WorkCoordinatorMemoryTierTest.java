package org.opensearch.migrations.bulkload.workcoordination;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.opensearch.migrations.bulkload.common.http.ConnectionContextTestParams;
import org.opensearch.migrations.testfixtures.SearchClusterContainer;
import org.opensearch.migrations.workcoordination.tracing.WorkCoordinationTestContext;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Memory-tier escalation behavior against real coordinator clusters, exercising both coordinator
 * variants (OS 2.x via OpenSearchWorkCoordinator_OS_2_11 and ES 6.8 via
 * OpenSearchWorkCoordinator_ES_6_8): escalation guards/idempotence, exact-tier acquisition with
 * the missing-field-is-tier-0 rule, the control-doc lease caps, successor tier inheritance, the
 * dirty-lease startup sweep, and the outright rejection of clients at another script version.
 */
@Slf4j
@Tag("isolatedTest")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class WorkCoordinatorMemoryTierTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SearchClusterContainer container;
    private WorkCoordinatorFactory factory;
    private AbstractedHttpClient httpClient;

    // One container per coordinator variant keeps the suite fast while covering both script paths.
    static Stream<SearchClusterContainer.ContainerVersion> coordinatorVariants() {
        return Stream.of(SearchClusterContainer.ES_V6_8_23, SearchClusterContainer.OS_V2_19_4);
    }

    private static String workId(String indexName, int shardNumber, long startingDocId) {
        return new IWorkCoordinator.WorkItemAndDuration.WorkItem(indexName, shardNumber, startingDocId).toString();
    }

    void setup(SearchClusterContainer.ContainerVersion version) throws Exception {
        container = new SearchClusterContainer(version);
        container.start();
        factory = new WorkCoordinatorFactory(container.getContainerVersion().getVersion());
        httpClient = new CoordinateWorkHttpClient(ConnectionContextTestParams.builder()
            .host(container.getUrl())
            .build()
            .toConnectionContext());
        var testContext = WorkCoordinationTestContext.factory().noOtelTracking();
        try (var workCoordinator = factory.get(httpClient, 2, "setupWorker")) {
            workCoordinator.setup(testContext::createCoordinationInitializationStateContext);
        }
    }

    @AfterEach
    void tearDown() {
        if (container != null) {
            container.close();
            container = null;
        }
    }

    @SneakyThrows
    private JsonNode workItemSource(String workItemId) {
        var response = httpClient.makeJsonRequest(
            AbstractedHttpClient.POST_METHOD,
            OpenSearchWorkCoordinator.INDEX_BASENAME + "/_search",
            null,
            "{\"query\":{\"ids\":{\"values\":[\"" + workItemId + "\"]}}}");
        return MAPPER.readTree(response.getPayloadBytes()).path("hits").path("hits").path(0).path("_source");
    }

    /** Create and complete the shard_setup sentinel, as ShardWorkPreparer's run leaves it. */
    @SneakyThrows
    private void completeShardSetupSentinel(OpenSearchWorkCoordinator coordinator,
                                            WorkCoordinationTestContext context) {
        coordinator.createOrUpdateLeaseForDocument(OpenSearchWorkCoordinator.SHARD_SETUP_DOC_ID, 1);
        coordinator.completeWorkItem(OpenSearchWorkCoordinator.SHARD_SETUP_DOC_ID,
            context::createCompleteWorkContext);
    }

    @SneakyThrows
    private void publishLeaseCaps(OpenSearchWorkCoordinator coordinator, String maxLeasesJson) {
        // The monitor partial-updates maxLeases on the shard_setup sentinel each sweep, using the
        // variant's update path (typeless _update on OS; typed doc/.../_update on ES 6.8, which
        // has no typeless _update endpoint).
        var docPath = coordinator.getPathForUpdates(OpenSearchWorkCoordinator.SHARD_SETUP_DOC_ID);
        var response = httpClient.makeJsonRequest("POST", docPath, null,
            "{\"doc\":{\"" + OpenSearchWorkCoordinator.MAX_LEASES_FIELD_NAME + "\":"
                + maxLeasesJson + "}}");
        Assertions.assertTrue(response.getStatusCode() / 100 == 2,
            "lease-cap publish failed: " + response.toDiagnosticString());
    }

    @ParameterizedTest
    @MethodSource("coordinatorVariants")
    void escalationGuardsAndIdempotence(SearchClusterContainer.ContainerVersion version) throws Exception {
        setup(version);
        var context = WorkCoordinationTestContext.factory().noOtelTracking();
        var item = workId("heavy", 0, 0);
        try (var worker = factory.get(httpClient, 3600, "pod1_uuidA")) {
            worker.createUnassignedWorkItem(item, context::createUnassignedWorkContext);
            var outcome = worker.acquireNextWorkItem(Duration.ofMinutes(10), context::createAcquireNextItemContext);
            Assertions.assertInstanceOf(IWorkCoordinator.WorkItemAndDuration.class, outcome);

            var leased = workItemSource(item);
            Assertions.assertEquals("3.2", leased.path("scriptVersion").asText());
            Assertions.assertTrue(leased.path(OpenSearchWorkCoordinator.LEASE_ACQUIRED_AT_FIELD_NAME).asLong() > 0,
                "acquisition must stamp leaseAcquiredAt");

            // Wrong observed holder: no-op.
            Assertions.assertFalse(worker.escalateWorkItem(item, "someone-else", 1));
            Assertions.assertEquals("pod1_uuidA", workItemSource(item).path("leaseHolderId").asText());

            // Correct observed holder: escalates, clears the lease, restores the exponent.
            Assertions.assertTrue(worker.escalateWorkItem(item, "pod1_uuidA", 1));
            var escalated = workItemSource(item);
            Assertions.assertEquals(1, escalated.path(OpenSearchWorkCoordinator.REQUIRED_MEMORY_TIER_FIELD_NAME).asInt());
            Assertions.assertEquals(0, escalated.path("expiration").asLong());
            Assertions.assertTrue(escalated.path("leaseHolderId").isNull());
            Assertions.assertEquals("3.2", escalated.path("scriptVersion").asText());
            Assertions.assertEquals(
                escalated.path("acquisitionLeaseExponent").asLong(),
                escalated.path("nextAcquisitionLeaseExponent").asLong(),
                "a tier bump is not a time problem: the lease exponent must be restored");

            // Second look finds a cleared lease: idempotent no-op, tier untouched.
            Assertions.assertFalse(worker.escalateWorkItem(item, "pod1_uuidA", 2));
            Assertions.assertEquals(1, workItemSource(item)
                .path(OpenSearchWorkCoordinator.REQUIRED_MEMORY_TIER_FIELD_NAME).asInt());
        }

        // The tier only ratchets up: a worker already at a lower tier can't pull it back down.
        try (var tier3Worker = factory.get(httpClient, 3600, "pod2_uuidB")) {
            tier3Worker.setWorkerMemoryTier(1);
            var outcome = tier3Worker.acquireNextWorkItem(Duration.ofMinutes(10), context::createAcquireNextItemContext);
            Assertions.assertInstanceOf(IWorkCoordinator.WorkItemAndDuration.class, outcome);
            // Manually push the tier up, as if a later escalation had raced this one upward.
            httpClient.makeJsonRequest("POST", tier3Worker.getPathForUpdates(item), null,
                "{\"doc\":{\"" + OpenSearchWorkCoordinator.REQUIRED_MEMORY_TIER_FIELD_NAME + "\":3}}");
            Assertions.assertTrue(tier3Worker.escalateWorkItem(item, "pod2_uuidB", 2));
            Assertions.assertEquals(3, workItemSource(item)
                .path(OpenSearchWorkCoordinator.REQUIRED_MEMORY_TIER_FIELD_NAME).asInt(),
                "escalation takes max(current, requested) — the tier never decreases");
        }

        // Escalation after completion: no-op.
        try (var finisher = factory.get(httpClient, 3600, "pod3_uuidC")) {
            finisher.setWorkerMemoryTier(3);
            var outcome = finisher.acquireNextWorkItem(Duration.ofMinutes(10), context::createAcquireNextItemContext);
            Assertions.assertInstanceOf(IWorkCoordinator.WorkItemAndDuration.class, outcome);
            finisher.completeWorkItem(item, context::createCompleteWorkContext);
            Assertions.assertFalse(finisher.escalateWorkItem(item, "pod3_uuidC", 4));
            Assertions.assertTrue(workItemSource(item).has("completedAt"));
        }
    }

    @ParameterizedTest
    @MethodSource("coordinatorVariants")
    void exactTierMatchingAndDecisionTableEndToEnd(SearchClusterContainer.ContainerVersion version) throws Exception {
        setup(version);
        var context = WorkCoordinationTestContext.factory().noOtelTracking();
        var ordinary = workId("ordinary", 0, 0);
        var heavy = workId("heavy", 0, 0);

        try (var creator = factory.get(httpClient, 3600, "creator_c")) {
            creator.createUnassignedWorkItem(ordinary, context::createUnassignedWorkContext);
            creator.createUnassignedWorkItem(heavy, context::createUnassignedWorkContext);
        }

        // Escalate "heavy" to tier 1 via an acquire + escalation.
        try (var victim = factory.get(httpClient, 3600, "podv_uuidV")) {
            String heavyId = heavy;
            while (true) {
                var outcome = victim.acquireNextWorkItem(Duration.ofMinutes(10), context::createAcquireNextItemContext);
                var acquired = Assertions.assertInstanceOf(IWorkCoordinator.WorkItemAndDuration.class, outcome);
                if (acquired.getWorkItem().toString().equals(heavyId)) {
                    Assertions.assertTrue(victim.escalateWorkItem(heavyId, "podv_uuidV", 1));
                    break;
                }
                victim.releaseWorkItem(acquired.getWorkItem().toString(),
                    IWorkCoordinator.LeaseReleaseReason.EARLY_EXIT, context::createReleaseWorkItemContext);
            }
        }

        // A tier-1 worker sees exactly the escalated item (never the tier-0 one).
        try (var tier1 = factory.get(httpClient, 3600, "pod1_uuid1")) {
            tier1.setWorkerMemoryTier(1);
            var outcome = tier1.acquireNextWorkItem(Duration.ofMinutes(10), context::createAcquireNextItemContext);
            var acquired = Assertions.assertInstanceOf(IWorkCoordinator.WorkItemAndDuration.class, outcome);
            Assertions.assertEquals(heavy, acquired.getWorkItem().toString());

            // With the tier-1 item leased, another tier-1 worker has nothing eligible (exit-4 shape).
            try (var tier1b = factory.get(httpClient, 3600, "pod2_uuid2")) {
                tier1b.setWorkerMemoryTier(1);
                var idleOutcome = tier1b.acquireNextWorkItem(Duration.ofMinutes(10), context::createAcquireNextItemContext);
                Assertions.assertInstanceOf(IWorkCoordinator.NoAvailableWorkToBeDone.class, idleOutcome);
            }

            tier1.completeWorkItem(heavy, context::createCompleteWorkContext);
        }

        // Tier-0 worker still matches the field-less ordinary item (missing field IS tier 0)...
        try (var tier0 = factory.get(httpClient, 3600, "pod3_uuid3")) {
            var outcome = tier0.acquireNextWorkItem(Duration.ofMinutes(10), context::createAcquireNextItemContext);
            var acquired = Assertions.assertInstanceOf(IWorkCoordinator.WorkItemAndDuration.class, outcome);
            Assertions.assertEquals(ordinary, acquired.getWorkItem().toString());
            tier0.completeWorkItem(ordinary, context::createCompleteWorkContext);

            // ...and once everything is complete, the precheck reports a full drain (exit-6 shape).
            var drained = tier0.acquireNextWorkItem(Duration.ofMinutes(10), context::createAcquireNextItemContext);
            Assertions.assertInstanceOf(IWorkCoordinator.NoWorkLeftToAcquire.class, drained);
        }
    }

    @ParameterizedTest
    @MethodSource("coordinatorVariants")
    void leaseCapIdlesOwnTierOnly(SearchClusterContainer.ContainerVersion version) throws Exception {
        setup(version);
        var context = WorkCoordinationTestContext.factory().noOtelTracking();
        try (var worker = factory.get(httpClient, 3600, "pod1_uuid1")) {
            // The caps live on the completed shard_setup sentinel, like after a real setup phase.
            completeShardSetupSentinel(worker, context);
            worker.createUnassignedWorkItem(workId("idx", 0, 0), context::createUnassignedWorkContext);
            worker.createUnassignedWorkItem(workId("idx", 1, 0), context::createUnassignedWorkContext);

            // Cap 0 for tier 0 (the manual-approval shape): the worker idles with the cap outcome.
            publishLeaseCaps(worker, "{\"0\": 0}");
            var capped = worker.acquireNextWorkItem(Duration.ofMinutes(10), context::createAcquireNextItemContext);
            Assertions.assertInstanceOf(IWorkCoordinator.LeaseCapReached.class, capped);

            // Cap 1: one acquisition succeeds; with one live lease the next attempt idles again.
            publishLeaseCaps(worker, "{\"0\": 1}");
            var first = worker.acquireNextWorkItem(Duration.ofMinutes(10), context::createAcquireNextItemContext);
            var firstAcquired = Assertions.assertInstanceOf(IWorkCoordinator.WorkItemAndDuration.class, first);
            var second = worker.acquireNextWorkItem(Duration.ofMinutes(10), context::createAcquireNextItemContext);
            Assertions.assertInstanceOf(IWorkCoordinator.LeaseCapReached.class, second);

            // A cap on a DIFFERENT tier never idles this one (no herding by construction).
            // Complete the held item first: a worker holds at most one lease at a time.
            worker.completeWorkItem(firstAcquired.getWorkItem().toString(), context::createCompleteWorkContext);
            publishLeaseCaps(worker, "{\"1\": 0}");
            var third = worker.acquireNextWorkItem(Duration.ofMinutes(10), context::createAcquireNextItemContext);
            Assertions.assertInstanceOf(IWorkCoordinator.WorkItemAndDuration.class, third);
        }
    }

    @ParameterizedTest
    @MethodSource("coordinatorVariants")
    void successorsInheritTheCreatingWorkersTier(SearchClusterContainer.ContainerVersion version) throws Exception {
        setup(version);
        var context = WorkCoordinationTestContext.factory().noOtelTracking();
        var parent = workId("big-index", 2, 0);
        var successor = workId("big-index", 2, 500);
        try (var creator = factory.get(httpClient, 3600, "creator_c")) {
            creator.createUnassignedWorkItem(parent, context::createUnassignedWorkContext);
            httpClient.makeJsonRequest("POST", creator.getPathForUpdates(parent), null,
                "{\"doc\":{\"" + OpenSearchWorkCoordinator.REQUIRED_MEMORY_TIER_FIELD_NAME + "\":2}}");
        }
        try (var tier2 = factory.get(httpClient, 3600, "pod1_uuid1")) {
            tier2.setWorkerMemoryTier(2);
            var outcome = tier2.acquireNextWorkItem(Duration.ofMinutes(10), context::createAcquireNextItemContext);
            var acquired = Assertions.assertInstanceOf(IWorkCoordinator.WorkItemAndDuration.class, outcome);
            Assertions.assertEquals(parent, acquired.getWorkItem().toString());
            tier2.createSuccessorWorkItemsAndMarkComplete(parent, List.of(successor), 0,
                context::createSuccessorWorkItemsContext);
        }
        var successorDoc = workItemSource(successor);
        Assertions.assertEquals(2,
            successorDoc.path(OpenSearchWorkCoordinator.REQUIRED_MEMORY_TIER_FIELD_NAME).asInt(),
            "successor items inherit the creating worker's tier");
        Assertions.assertEquals("big-index", successorDoc.path("indexName").asText(),
            "successors carry the plaintext index name for per-index rollups");
        Assertions.assertEquals("3.2", successorDoc.path("scriptVersion").asText());

        // Tier-0 successors keep the pre-3.2 document shape (no tier field).
        var t0parent = workId("small-index", 0, 0);
        var t0successor = workId("small-index", 0, 100);
        try (var tier0 = factory.get(httpClient, 3600, "pod2_uuid2")) {
            tier0.createUnassignedWorkItem(t0parent, context::createUnassignedWorkContext);
            var outcome = tier0.acquireNextWorkItem(Duration.ofMinutes(10), context::createAcquireNextItemContext);
            Assertions.assertInstanceOf(IWorkCoordinator.WorkItemAndDuration.class, outcome);
            tier0.createSuccessorWorkItemsAndMarkComplete(t0parent, List.of(t0successor), 0,
                context::createSuccessorWorkItemsContext);
        }
        Assertions.assertFalse(workItemSource(t0successor)
                .has(OpenSearchWorkCoordinator.REQUIRED_MEMORY_TIER_FIELD_NAME),
            "tier-0 successors must not gain the field");
    }

    @ParameterizedTest
    @MethodSource("coordinatorVariants")
    void dirtyLeaseSweepEscalatesOnOomAndReleasesOtherwise(
        SearchClusterContainer.ContainerVersion version) throws Exception {
        setup(version);
        var context = WorkCoordinationTestContext.factory().noOtelTracking();
        var oomItem = workId("oom-index", 0, 0);
        var crashItem = workId("crash-index", 0, 0);
        var abandonedItem = workId("abandoned-index", 0, 0);

        // Three "predecessor" processes in pod1 (a worker holds at most one lease at a time)
        // each lease one item and die hard (no release); the third deliberately abandons its.
        try (var creator = factory.get(httpClient, 3600, "pod1_creatoruuid")) {
            for (var item : List.of(oomItem, crashItem, abandonedItem)) {
                creator.createUnassignedWorkItem(item, context::createUnassignedWorkContext);
            }
        }
        String abandonedHolder = null;
        for (int i = 0; i < 3; i++) {
            var holderId = "pod1_deaduuid" + i;
            try (var predecessor = factory.get(httpClient, 3600, holderId)) {
                var outcome = predecessor.acquireNextWorkItem(Duration.ofMinutes(30),
                    context::createAcquireNextItemContext);
                var acquired = Assertions.assertInstanceOf(
                    IWorkCoordinator.WorkItemAndDuration.class, outcome);
                if (acquired.getWorkItem().toString().equals(abandonedItem)) {
                    predecessor.markLeaseAbandoned(abandonedItem);
                    abandonedHolder = holderId;
                }
            }
        }
        Assertions.assertNotNull(abandonedHolder, "one predecessor should have drawn the abandoned item");
        var oomHolder = workItemSource(oomItem).path("leaseHolderId").asText();
        var crashHolder = workItemSource(crashItem).path("leaseHolderId").asText();

        // Case (d-ish): a different pod's worker must see nothing dirty here.
        try (var otherPod = factory.get(httpClient, 3600, "pod9_other-uuid")) {
            Assertions.assertTrue(otherPod.findDirtyLeases("pod9_", "pod9_other-uuid").isEmpty());
        }

        // The successor process in the same pod finds the two non-abandoned leases.
        try (var successor = factory.get(httpClient, 3600, "pod1_new-uuid")) {
            var dirty = successor.findDirtyLeases("pod1_", "pod1_new-uuid");
            Assertions.assertEquals(2, dirty.size(), "abandoned lease must be skipped: " + dirty);

            // The supervisor reports the OOM-item holder as heap OOM and the other as an uncaught
            // exception; the abandoned item's holder as OOM too, which must not matter.
            var crashes = Map.of(
                DirtyLeaseSweeper.instanceIdOf(oomHolder), "3",
                DirtyLeaseSweeper.instanceIdOf(crashHolder), "1",
                DirtyLeaseSweeper.instanceIdOf(abandonedHolder), "3");
            DirtyLeaseSweeper.runStartupSweep(successor, "pod1_new-uuid", 0, crashes, null);

            var oomDoc = workItemSource(oomItem);
            Assertions.assertEquals(0, oomDoc.path("expiration").asLong(), "OOM item lease must be cleared");
            Assertions.assertEquals(1,
                oomDoc.path(OpenSearchWorkCoordinator.REQUIRED_MEMORY_TIER_FIELD_NAME).asInt(),
                "the OOM item must be escalated to tier 1");
            var crashDoc = workItemSource(crashItem);
            Assertions.assertEquals(0, crashDoc.path("expiration").asLong(), "crash item lease must be cleared");
            Assertions.assertEquals(0,
                crashDoc.path(OpenSearchWorkCoordinator.REQUIRED_MEMORY_TIER_FIELD_NAME).asInt(0),
                "a non-OOM crash must be released at the same tier");
            var abandonedDoc = workItemSource(abandonedItem);
            Assertions.assertEquals(abandonedHolder, abandonedDoc.path("leaseHolderId").asText(),
                "the deliberately-abandoned lease must be left alone");

            // Idempotence: a second sweep finds nothing (the leases were cleared).
            Assertions.assertTrue(successor.findDirtyLeases("pod1_", "pod1_new-uuid").isEmpty());
        }

        // No-evidence variant: a hard kill the supervisor didn't report releases without a tier bump.
        try (var secondVictim = factory.get(httpClient, 3600, "pod2_dead-uuid")) {
            secondVictim.setWorkerMemoryTier(1);
            var outcome = secondVictim.acquireNextWorkItem(Duration.ofMinutes(30),
                context::createAcquireNextItemContext);
            var acquired = Assertions.assertInstanceOf(IWorkCoordinator.WorkItemAndDuration.class, outcome);
            var itemId = acquired.getWorkItem().toString();
            try (var podmate = factory.get(httpClient, 3600, "pod2_new-uuid")) {
                podmate.setWorkerMemoryTier(1);
                DirtyLeaseSweeper.runStartupSweep(podmate, "pod2_new-uuid", 1, Map.of("some-other-id", "3"), null);
            }
            var doc = workItemSource(itemId);
            Assertions.assertEquals(0, doc.path("expiration").asLong(), "lease recovered immediately");
            Assertions.assertEquals(1,
                doc.path(OpenSearchWorkCoordinator.REQUIRED_MEMORY_TIER_FIELD_NAME).asInt(),
                "a non-OOM hard crash must NOT bump the tier");
        }
    }

    @ParameterizedTest
    @MethodSource("coordinatorVariants")
    void oldClientsAreRejectedOutright(SearchClusterContainer.ContainerVersion version) throws Exception {
        // There is no cross-version document support: the scriptVersion exists solely so a client
        // from a different deployment is rejected outright on every document of this session.
        setup(version);
        var context = WorkCoordinationTestContext.factory().noOtelTracking();
        var item = workId("fenced", 0, 0);
        try (var worker = factory.get(httpClient, 3600, "pod1_uuid1")) {
            worker.createUnassignedWorkItem(item, context::createUnassignedWorkContext);
            Assertions.assertEquals("3.2", workItemSource(item).path("scriptVersion").asText());

            // Emulate a lingering 3.1 client: its scripts throw on any other version.
            var oldStyleAcquire = "{\n"
                + "  \"script\": {\n"
                + "    \"lang\": \"painless\",\n"
                + "    \"params\": {\"workerId\": \"old-worker\"},\n"
                + "    \"source\": \""
                + "      if (ctx._source.scriptVersion != \\\"3.1\\\") {"
                + "        throw new IllegalArgumentException(\\\"scriptVersion mismatch.\\\");"
                + "      }"
                + "      ctx._source.expiration = 9999999999L;"
                + "      ctx._source.leaseHolderId = params.workerId;"
                + "\"\n"
                + "  }\n"
                + "}";
            var response = httpClient.makeJsonRequest("POST", worker.getPathForUpdates(item), null, oldStyleAcquire);
            Assertions.assertTrue(response.getStatusCode() >= 400,
                "a 3.1 client's script must fail on a 3.2 document, got "
                    + response.toDiagnosticString());
            var doc = workItemSource(item);
            Assertions.assertTrue(doc.path("leaseHolderId").isMissingNode() || doc.path("leaseHolderId").isNull(),
                "the old client must not have leased the item");
        }
    }
}
