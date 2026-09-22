package com.github.lisama569.biodbaggregatorjava.tests;

import com.github.lisama569.biodbaggregatorjava.model.ImportReport;
import com.github.lisama569.biodbaggregatorjava.model.ResolvedEntity;
import com.github.lisama569.biodbaggregatorjava.model.SourceFormat;
import com.github.lisama569.biodbaggregatorjava.service.AggregatorService;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Concurrency tests: version refresh/switch racing with many queries must never expose a
 * mixed-version view, and repeated parallel imports must stay idempotent.
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class ConcurrentSnapshotConsistencyTest {

    private static final Logger log = LoggerFactory.getLogger(ConcurrentSnapshotConsistencyTest.class);

    @Autowired
    private AggregatorService service;

    private static String payload(String namePrefix, int count) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 1; i <= count; i++) {
            if (i > 1) {
                sb.append(',');
            }
            sb.append("{\"id\":\"N").append(i).append("\",\"type\":\"gene\",\"symbol\":\"G")
              .append(i).append("\",\"name\":\"").append(namePrefix).append("-name-").append(i)
              .append("\",\"organism\":\"Homo sapiens\"}");
        }
        return sb.append(']').toString();
    }

    @Test
    void queriesDuringRefreshAndSwitchNeverSeeMixedViews() throws Exception {
        final String va = "va";
        final String vb = "vb";
        service.importSnapshot(va, "refseq", SourceFormat.JSON, payload("va", 20));
        service.importSnapshot(vb, "refseq", SourceFormat.JSON, payload("vb", 21));
        service.switchVersion(va);

        // One writer hammers version switching; another republishes identical snapshots,
        // forcing concurrent rebuilds/publishes while readers query.
        ExecutorService pool = Executors.newFixedThreadPool(10);
        AtomicBoolean stop = new AtomicBoolean(false);
        AtomicInteger queries = new AtomicInteger();
        List<Throwable> failures = java.util.Collections.synchronizedList(new ArrayList<>());
        CountDownLatch start = new CountDownLatch(1);

        pool.submit(() -> {
            try {
                start.await();
                while (!stop.get()) {
                    service.switchVersion(vb);
                    service.switchVersion(va);
                }
            } catch (Throwable t) {
                failures.add(t);
            }
        });
        pool.submit(() -> {
            try {
                start.await();
                while (!stop.get()) {
                    // idempotent refresh republishes the same immutable view
                    ImportReport r = service.importSnapshot(va, "refseq", SourceFormat.JSON, payload("va", 20));
                    if (!"DUPLICATE".equals(r.status())) {
                        failures.add(new AssertionError("expected DUPLICATE, got " + r.status()));
                    }
                }
            } catch (Throwable t) {
                failures.add(t);
            }
        });

        for (int t = 0; t < 8; t++) {
            pool.submit(() -> {
                try {
                    start.await();
                    while (!stop.get()) {
                        // Each query is an independent single-version read; it must always
                        // return a complete, internally consistent view. We do not compare
                        // across two separate queries because current may switch between them
                        // (that is the expected linearizable behavior, not a mixed view).
                        List<ResolvedEntity> all = service.queryAll();
                        validateSingleVersionView(all);
                        // A point lookup must itself return a well-formed entity carrying a
                        // consistent snapshot/rules stamp, or empty (version switched).
                        if (!all.isEmpty()) {
                            String localId = all.get(0).identifiersBySource().get("refseq");
                            if (localId != null) {
                                service.resolveIdentifier(localId)
                                        .ifPresent(ConcurrentSnapshotConsistencyTest::validateEntityStamps);
                            }
                        }
                        queries.incrementAndGet();
                    }
                } catch (Throwable ex) {
                    failures.add(ex);
                }
            });
        }

        start.countDown();
        Thread.sleep(3000);
        stop.set(true);
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "threads did not finish");
        log.info("concurrent read/switch test: totalQueries={} failures={}",
                queries.get(), failures.size());
        assertTrue(failures.isEmpty(),
                "no mixed views allowed: " + failures.stream()
                        .findFirst().map(Throwable::getMessage).orElse(""));
        assertTrue(queries.get() > 100, "expected substantial query volume, got " + queries.get());
    }

    /**
     * Every query result must be a complete view of exactly one version:
     * all entities stamped with the same snapshotVersion, correct cardinality and values,
     * never a mix of va/vb names.
     */
    private void validateSingleVersionView(List<ResolvedEntity> all) {
        if (all.isEmpty()) {
            return;
        }
        String version = all.get(0).snapshotVersion();
        assertTrue(version.equals("va") || version.equals("vb"), "unknown version: " + version);
        int expectedCount = version.equals("va") ? 20 : 21;
        String expectedPrefix = version.equals("va") ? "va-name-" : "vb-name-";
        for (ResolvedEntity e : all) {
            validateEntityStamps(e);
            assertTrue(e.snapshotVersion().equals(version),
                    "entity from another version mixed into view " + version);
            assertTrue(e.canonicalName().startsWith(expectedPrefix),
                    "value from other version seen in " + version + ": " + e.canonicalName());
        }
        assertEquals(expectedCount, all.size(),
                "mixed/incomplete view for version " + version);
    }

    private static void validateEntityStamps(ResolvedEntity e) {
        assertTrue(e.snapshotVersion().equals("va") || e.snapshotVersion().equals("vb"),
                "unknown version stamp: " + e.snapshotVersion());
        assertEquals("priority-rules-v1", e.rulesVersion());
        assertNotNull(e.entityKey());
    }

    @Test
    void parallelIdenticalImportsAreIdempotent() throws Exception {
        int threads = 12;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger created = new AtomicInteger();
        AtomicInteger duplicate = new AtomicInteger();
        AtomicInteger conflicting = new AtomicInteger();
        List<Throwable> failures = java.util.Collections.synchronizedList(new ArrayList<>());
        String content = payload("idem", 3);

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    ImportReport r = service.importSnapshot(
                            "idem-v", "refseq", SourceFormat.JSON, content);
                    switch (r.status()) {
                        case "CREATED" -> created.incrementAndGet();
                        case "DUPLICATE" -> duplicate.incrementAndGet();
                        default -> conflicting.incrementAndGet();
                    }
                } catch (Throwable t) {
                    failures.add(t);
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        log.info("parallel identical imports: CREATED={} DUPLICATE={} CONFLICT={} failures={}",
                created.get(), duplicate.get(), conflicting.get(), failures.size());

        assertTrue(failures.isEmpty());
        assertEquals(1, created.get(), "exactly one import creates the source");
        assertEquals(threads - 1, duplicate.get(), "all other parallel imports are idempotent replays");
        assertEquals(0, conflicting.get());

        // no duplicated records and no count drift after the storm
        List<ResolvedEntity> all = service.queryAll();
        assertEquals(3, all.size(), "exactly three records after parallel identical imports");
        long distinctKeys = all.stream().map(ResolvedEntity::entityKey).distinct().count();
        assertEquals(3, distinctKeys, "no duplicate entity keys");
    }

    @Test
    void differentContentParallelImportNeverOverwritesReleasedVersion() throws Exception {
        String original = payload("orig", 2);
        assertEquals("CREATED",
                service.importSnapshot("imm-v", "refseq", SourceFormat.JSON, original).status());

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger duplicated = new AtomicInteger();
        AtomicInteger conflicted = new AtomicInteger();
        for (int i = 0; i < threads; i++) {
            final boolean replay = i % 2 == 0;
            pool.submit(() -> {
                try {
                    start.await();
                    String body = replay ? original : payload("tampered", 5);
                    ImportReport r = service.importSnapshot(
                            "imm-v", "refseq", SourceFormat.JSON, body);
                    if ("DUPLICATE".equals(r.status())) {
                        duplicated.incrementAndGet();
                    } else if ("CONFLICTING_REIMPORT".equals(r.status())) {
                        conflicted.incrementAndGet();
                    }
                } catch (Throwable ignored) {
                    // contention outcomes are asserted below; exceptions would empty counters
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        log.info("parallel mixed imports: duplicateReplies={} conflictingRejections={}",
                duplicated.get(), conflicted.get());
        assertEquals(4, duplicated.get());
        assertEquals(4, conflicted.get());
        assertEquals(2, service.queryAll().size(), "released version remains unchanged");
    }
}
