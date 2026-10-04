package com.github.lisama569.biodbaggregatorjava.tests;

import com.github.lisama569.biodbaggregatorjava.model.FieldDecisionDifference;
import com.github.lisama569.biodbaggregatorjava.model.ResolutionComparison;
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
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * While two-rule-set comparisons run continuously, other threads concurrently:
 * <ol>
 *   <li>idempotently re-import each source (forcing view republish / re-resolution),</li>
 *   <li>switch the current version back and forth,</li>
 *   <li>activate/deactivate the two rule sets in turn.</li>
 * </ol>
 * Every comparison result must be a complete conclusion pair of ONE snapshot version and
 * the SAME two rule sets: never half-old/half-new data, never absent, always deterministic.
 * The same data + same two rule sets must yield byte-identical comparison results.
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class ConcurrentComparisonConsistencyTest {

    private static final Logger log =
            LoggerFactory.getLogger(ConcurrentComparisonConsistencyTest.class);

    private static final String R_REFSEQ = "priority-rules-v1";
    private static final String R_ENSEMBL = "priority-rules-ensembl-v1";

    // Every field is byte-identical across the two sources, so the comparison must always
    // report four WINNER_SOURCE_CHANGED diffs (one per field), never value diffs.
    private static final String REFSEQ = """
            [
              {"id":"NM1","type":"gene","symbol":"HBB","name":"beta globin",
               "sequence":"MVLSPADK","organism":"Homo sapiens",
               "xrefs":{"ensembl":"E1"}}
            ]
            """;
    private static final String ENSEMBL =
            "REC|E1|gene|HBB|beta globin|MVLSPADK|Homo sapiens|refseq:NM1\n";

    @Autowired
    private AggregatorService service;

    private void seed(String version, String rules) {
        service.importSnapshot(version, "refseq", SourceFormat.JSON, REFSEQ, rules);
        service.importSnapshot(version, "ensembl", SourceFormat.FLAT, ENSEMBL, rules);
    }

    @Test
    void comparisonsStayWholeAndDeterministicUnderConcurrentMutations() throws Exception {
        seed("cmp-a", R_REFSEQ);
        seed("cmp-b", R_REFSEQ);
        service.switchVersion("cmp-a");

        // Canonical expected shapes, computed once before the storm for each version.
        ResolutionComparison canonicalA =
                service.compareResolutions("cmp-a", R_REFSEQ, R_ENSEMBL);
        ResolutionComparison canonicalB =
                service.compareResolutions("cmp-b", R_REFSEQ, R_ENSEMBL);
        validateShape("cmp-a", canonicalA, false);
        validateShape("cmp-b", canonicalB, false);

        ExecutorService pool = Executors.newFixedThreadPool(10);
        AtomicBoolean stop = new AtomicBoolean(false);
        AtomicInteger comparisons = new AtomicInteger();
        List<Throwable> failures = java.util.Collections.synchronizedList(new ArrayList<>());
        CountDownLatch start = new CountDownLatch(1);

        // writer 1: idempotent re-imports force republish/re-resolution on both versions
        pool.submit(() -> {
            try {
                start.await();
                while (!stop.get()) {
                    service.importSnapshot("cmp-a", "refseq", SourceFormat.JSON,
                            REFSEQ, R_REFSEQ);
                    service.importSnapshot("cmp-b", "ensembl", SourceFormat.FLAT,
                            ENSEMBL, R_REFSEQ);
                }
            } catch (Throwable t) {
                failures.add(t);
            }
        });
        // writer 2: flip current version
        pool.submit(() -> {
            try {
                start.await();
                while (!stop.get()) {
                    service.switchVersion("cmp-b");
                    service.switchVersion("cmp-a");
                }
            } catch (Throwable t) {
                failures.add(t);
            }
        });
        // writer 3: flip the active rule-set pointer (must not affect explicit comparisons)
        pool.submit(() -> {
            try {
                start.await();
                while (!stop.get()) {
                    service.activateRuleSet(R_ENSEMBL);
                    service.activateRuleSet(R_REFSEQ);
                }
            } catch (Throwable t) {
                failures.add(t);
            }
        });

        // readers: hammer explicit-version comparisons in both directions
        for (int t = 0; t < 6; t++) {
            final boolean reverse = t % 2 == 1;
            pool.submit(() -> {
                try {
                    start.await();
                    while (!stop.get()) {
                        for (String version : List.of("cmp-a", "cmp-b")) {
                            ResolutionComparison c = reverse
                                    ? service.compareResolutions(version, R_ENSEMBL, R_REFSEQ)
                                    : service.compareResolutions(version, R_REFSEQ, R_ENSEMBL);
                            ResolutionComparison canonical =
                                    version.equals("cmp-a") ? canonicalA : canonicalB;
                            validateShape(version, c, reverse);
                            // Determinism: same data + same ordered rule pair => exact result.
                            ResolutionComparison expected = reverse ? swapped(canonical) : canonical;
                            if (!expected.equals(c)) {
                                failures.add(new AssertionError(
                                        "non-deterministic comparison for " + version));
                            }
                            comparisons.incrementAndGet();
                        }
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
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        log.info("concurrent comparison test: totalComparisons={} failures={}",
                comparisons.get(), failures.size());
        assertTrue(comparisons.get() > 100,
                "expected substantial comparison volume, got " + comparisons.get());
        assertTrue(failures.isEmpty(),
                "every comparison must be a complete single-version/two-rule conclusion: "
                        + failures.stream().findFirst().map(Throwable::getMessage).orElse(""));
    }

    /** Structural invariants of the source-only-flip comparison for this fixture. */
    private void validateShape(String version, ResolutionComparison c, boolean reverse) {
        assertEquals(version, c.snapshotVersion());
        String expectedA = reverse ? R_ENSEMBL : R_REFSEQ;
        String expectedB = reverse ? R_REFSEQ : R_ENSEMBL;
        assertEquals(expectedA, c.rulesSetA());
        assertEquals(expectedB, c.rulesSetB());
        assertFalse(c.equivalent(), "four identical-value fields still flip attribution");
        assertTrue(c.clusterDifferences().isEmpty(),
                "xref merging is rule-independent");
        Map<String, List<FieldDecisionDifference>> byField = c.fieldDifferences().stream()
                .collect(Collectors.groupingBy(FieldDecisionDifference::field));
        for (String f : List.of("symbol", "name", "sequence", "organism")) {
            List<FieldDecisionDifference> diffs = byField.get(f);
            assertEquals(1, diffs == null ? 0 : diffs.size(),
                    "exactly one " + f + " diff: " + c.fieldDifferences());
            FieldDecisionDifference d = diffs.get(0);
            assertEquals(com.github.lisama569.biodbaggregatorjava.model.FieldDifferenceKind
                    .WINNER_SOURCE_CHANGED, d.kind(), "values are identical for " + f);
            // credited winner is the data source favored by each side's rule set
            assertEquals(reverse ? "ensembl" : "refseq", d.winnerSourceA());
            assertEquals(reverse ? "refseq" : "ensembl", d.winnerSourceB());
            assertEquals(d.winnerValueA(), d.winnerValueB(), "value must match for " + f);
        }
        // exactly the four fields, nothing more, nothing less
        assertEquals(4, c.fieldDifferences().size(), c.fieldDifferences().toString());
    }

    /** A B-vs-A comparison is the A-vs-B canonical one with sides exchanged. */
    private static ResolutionComparison swapped(ResolutionComparison c) {
        List<FieldDecisionDifference> fields = c.fieldDifferences().stream()
                .map(d -> new FieldDecisionDifference(d.signature(), d.field(), d.kind(),
                        d.winnerSourceB(), d.winnerValueB(),
                        d.winnerSourceA(), d.winnerValueA()))
                .toList();
        return new ResolutionComparison(c.snapshotVersion(), c.rulesSetB(), c.rulesSetA(),
                c.clusterDifferences(), fields,
                c.conflictsOnlyUnderB(), c.conflictsOnlyUnderA(),
                c.ambiguitiesOnlyUnderB(), c.ambiguitiesOnlyUnderA(), c.equivalent());
    }
}
