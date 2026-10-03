package com.github.lisama569.biodbaggregatorjava.tests;

import com.github.lisama569.biodbaggregatorjava.model.ResolutionReport;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Concurrency tests with rule switching in the mix:
 * queries (pinned and explicit-rule), imports, version switches and rule-set activation
 * run concurrently. Every single read must observe a complete conclusion of exactly one
 * (version, ruleSet) pair - never a half-old/half-new mixed adjudication - and the same
 * data under the same rule set must always give the same winner regardless of scheduling.
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class ConcurrentRuleConsistencyTest {

    private static final Logger log = LoggerFactory.getLogger(ConcurrentRuleConsistencyTest.class);

    private static final String R_REFSEQ = "priority-rules-v1";
    private static final String R_ENSEMBL = "priority-rules-ensembl-v1";

    @Autowired
    private AggregatorService service;

    private static String refseqPayload(String prefix) {
        return "[{\"id\":\"NM1\",\"type\":\"gene\",\"symbol\":\"HBB\","
                + "\"name\":\"" + prefix + "-refseq-name\","
                + "\"organism\":\"Homo sapiens\",\"xrefs\":{\"ensembl\":\"E1\"}}]";
    }

    private static final String ENSEMBL_PAYLOAD =
            "REC|E1|gene|HBB|zzz-ensembl-name||Homo sapiens|refseq:NM1\n";

    private void seedVersion(String version, String rules) {
        service.importSnapshot(version, "refseq", SourceFormat.JSON,
                refseqPayload(version), rules);
        service.importSnapshot(version, "ensembl", SourceFormat.FLAT, ENSEMBL_PAYLOAD, rules);
    }

    @Test
    void queriesDuringVersionAndRuleSwitchingNeverSeeMixedConclusions() throws Exception {
        seedVersion("cv-a", R_REFSEQ);
        seedVersion("cv-b", R_ENSEMBL);
        service.switchVersion("cv-a");

        ExecutorService pool = Executors.newFixedThreadPool(12);
        AtomicBoolean stop = new AtomicBoolean(false);
        AtomicInteger queries = new AtomicInteger();
        List<Throwable> failureLock =
                java.util.Collections.synchronizedList(new ArrayList<>());
        CountDownLatch start = new CountDownLatch(1);

        // writer 1: flip versions
        pool.submit(() -> {
            try {
                start.await();
                while (!stop.get()) {
                    service.switchVersion("cv-b");
                    service.switchVersion("cv-a");
                }
            } catch (Throwable t) {
                failureLock.add(t);
            }
        });
        // writer 2: flip the active rule set
        pool.submit(() -> {
            try {
                start.await();
                while (!stop.get()) {
                    service.activateRuleSet(R_ENSEMBL);
                    service.activateRuleSet(R_REFSEQ);
                }
            } catch (Throwable t) {
                failureLock.add(t);
            }
        });
        // writer 3: idempotent re-imports force concurrent republish/re-resolution
        pool.submit(() -> {
            try {
                start.await();
                while (!stop.get()) {
                    service.importSnapshot("cv-a", "refseq", SourceFormat.JSON,
                            refseqPayload("cv-a"), R_REFSEQ);
                    service.importSnapshot("cv-b", "ensembl", SourceFormat.FLAT,
                            ENSEMBL_PAYLOAD, R_ENSEMBL);
                }
            } catch (Throwable t) {
                failureLock.add(t);
            }
        });

        for (int t = 0; t < 9; t++) {
            final int mode = t % 3;
            pool.submit(() -> {
                try {
                    start.await();
                    while (!stop.get()) {
                        switch (mode) {
                            case 0 -> validatePinnedQuery(queries);
                            case 1 -> validateExplicitQuery(R_REFSEQ, "cv-a-refseq-name", queries);
                            default -> validateExplicitQuery(R_ENSEMBL, "zzz-ensembl-name", queries);
                        }
                    }
                } catch (Throwable ex) {
                    failureLock.add(ex);
                }
            });
        }

        start.countDown();
        Thread.sleep(3000);
        stop.set(true);
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        log.info("concurrent rule/version test: totalQueries={} failures={}",
                queries.get(), failureLock.size());
        assertTrue(queries.get() > 100, "expected substantial query volume, got " + queries.get());
        assertTrue(failureLock.isEmpty(),
                "no mixed (version,rules) conclusions allowed: "
                        + failureLock.stream().findFirst().map(th -> th.getMessage()).orElse(""));
    }

    /** A pinned (default) query: the returned conclusion must be internally consistent -
     *  exactly one snapshot version, exactly one rule-set stamp, and a winner matching
     *  that pair. We derive the version FROM the captured result; reading currentVersion
     *  separately would itself race with the switcher. */
    private void validatePinnedQuery(AtomicInteger queries) {
        List<ResolvedEntity> all = service.queryAll();
        if (all.isEmpty()) {
            return;
        }
        String version = all.get(0).snapshotVersion();
        String rules = all.get(0).rulesVersion();
        assertTrue(version.equals("cv-a") || version.equals("cv-b"),
                "unknown version stamp: " + version);
        String expectedRules = version.equals("cv-a") ? R_REFSEQ : R_ENSEMBL;
        String expectedName = version.equals("cv-a")
                ? "cv-a-refseq-name" : "zzz-ensembl-name";
        validateConsistentRead(all, version, expectedRules, expectedName);
        assertEquals(expectedRules, rules, "winner/stamp must match the captured version");
        queries.incrementAndGet();
    }

    /** An explicit-rule query always targets the same data semantics: resolve each
     *  existing version under the named rule set; winners must be deterministic. */
    private void validateExplicitQuery(String rules, String aExpected, AtomicInteger queries) {
        // Explicit resolution against a fixed version is independent of current/active and
        // must always return the same winner - this is the "same data + same rules => same
        // conclusion regardless of execution order" guarantee.
        ResolutionReport a = service.getResolution("cv-a", rules);
        assertEquals(rules, a.rulesVersion());
        String nameA = a.entities().get(0).canonicalName();
        assertEquals(aExpected, nameA,
                "deterministic winner for cv-a under " + rules + ", got " + nameA);
        for (ResolvedEntity e : a.entities()) {
            assertEquals("cv-a", e.snapshotVersion());
            assertEquals(rules, e.rulesVersion(),
                    "no half-old/half-new rules stamp inside one conclusion");
        }
        queries.incrementAndGet();
    }

    private void validateConsistentRead(List<ResolvedEntity> all, String version,
                                        String rules, String expectedName) {
        assertEquals(1, all.size(), "complete single entity view");
        for (ResolvedEntity e : all) {
            assertEquals(version, e.snapshotVersion(),
                    "entity from another version mixed into view");
            assertEquals(rules, e.rulesVersion(),
                    "entity stamped with another rule set - mixed conclusion");
            assertEquals(expectedName, e.canonicalName(),
                    "winner must match the pinned rule set of " + version);
        }
    }
}
