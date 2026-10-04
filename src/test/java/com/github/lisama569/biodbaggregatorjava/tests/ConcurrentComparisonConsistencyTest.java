package com.github.lisama569.biodbaggregatorjava.tests;

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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Comparison consistency under concurrency: while comparisons of one snapshot under two
 * rule sets run, other threads import (idempotent replays and genuinely new sources on a
 * DIFFERENT version), switch the current version and flip the active rule set.
 *
 * <p>Guarantees under test:</p>
 * <ul>
 *   <li>same data + same two rule sets =&gt; every comparison is byte-identical to the
 *       baseline, no matter what runs concurrently;</li>
 *   <li>a comparison never comes back half old-rules/half new-rules or half old-data/
 *       half new-data: both sides always derive from one captured view;</li>
 *   <li>comparisons of a concurrently growing version stay internally consistent
 *       (equivalent flag agrees with the diff lists, version stamp is exact).</li>
 * </ul>
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class ConcurrentComparisonConsistencyTest {

    private static final Logger log =
            LoggerFactory.getLogger(ConcurrentComparisonConsistencyTest.class);

    private static final String R_REFSEQ = "priority-rules-v1";
    private static final String R_ENSEMBL = "priority-rules-ensembl-v1";

    @Autowired
    private AggregatorService service;

    private static final String REFSEQ_A =
            "[{\"id\":\"NM1\",\"type\":\"gene\",\"symbol\":\"HBB\","
                    + "\"name\":\"shared name\",\"sequence\":\"ACGT\","
                    + "\"organism\":\"Homo sapiens\",\"xrefs\":{\"ensembl\":\"E1\"}}]";
    private static final String ENSEMBL_A =
            "REC|E1|gene|HBB|shared name|ACGT|Homo sapiens|refseq:NM1\n";

    private static String refseqB() {
        return "[{\"id\":\"NB1\",\"type\":\"gene\",\"symbol\":\"ALK\","
                + "\"name\":\"b-refseq-name\",\"xrefs\":{\"ensembl\":\"EB1\"}}]";
    }

    private static final String ENSEMBL_B =
            "REC|EB1|gene|ALK|b-ensembl-name|||refseq:NB1\n";

    private void seedStable() {
        service.importSnapshot("cc-a", "refseq", SourceFormat.JSON, REFSEQ_A, R_REFSEQ);
        service.importSnapshot("cc-a", "ensembl", SourceFormat.FLAT, ENSEMBL_A, R_REFSEQ);
    }

    private void seedGrowing() {
        service.importSnapshot("cc-b", "refseq", SourceFormat.JSON, refseqB(), R_REFSEQ);
        service.importSnapshot("cc-b", "ensembl", SourceFormat.FLAT, ENSEMBL_B, R_REFSEQ);
    }

    @Test
    void comparisonsAreStableAndUnmixedWhileTheWorldChangesAroundThem() throws Exception {
        seedStable();
        seedGrowing();
        service.switchVersion("cc-a");

        // baseline: same data + same two rule sets, computed before the storm
        ResolutionComparison baseline =
                service.compareResolutions("cc-a", R_REFSEQ, R_ENSEMBL);
        assertTrue(baseline.fieldDifferences().stream()
                        .anyMatch(d -> !d.valueChanged() && d.winnerSourceChanged()),
                "scenario must include same-value/different-signer fields");
        log.info("baseline: version=cc-a rulesA={} rulesB={} fieldDiffs={} equivalent={}",
                R_REFSEQ, R_ENSEMBL, baseline.fieldDifferences().size(),
                baseline.equivalent());

        ExecutorService pool = Executors.newFixedThreadPool(12);
        AtomicBoolean stop = new AtomicBoolean(false);
        AtomicInteger comparisons = new AtomicInteger();
        List<Throwable> failures =
                java.util.Collections.synchronizedList(new ArrayList<>());
        CountDownLatch start = new CountDownLatch(1);

        // writer 1: flip current version
        pool.submit(() -> {
            try {
                start.await();
                while (!stop.get()) {
                    service.switchVersion("cc-b");
                    service.switchVersion("cc-a");
                }
            } catch (Throwable t) {
                failures.add(t);
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
                failures.add(t);
            }
        });
        // writer 3: idempotent replays on cc-a (no data change) + grow cc-b once, then
        // keep replaying: imports and republishes race the readers
        pool.submit(() -> {
            try {
                start.await();
                boolean grown = false;
                while (!stop.get()) {
                    service.importSnapshot("cc-a", "refseq", SourceFormat.JSON,
                            REFSEQ_A, R_REFSEQ);
                    service.importSnapshot("cc-a", "ensembl", SourceFormat.FLAT,
                            ENSEMBL_A, R_REFSEQ);
                    if (!grown) {
                        service.importSnapshot("cc-b", "uniprot", SourceFormat.CSV,
                                "id,type,symbol,name,xrefs\n"
                                        + "Q9,gene,ALK,b-uniprot-name,refseq:NB1",
                                R_REFSEQ);
                        grown = true;
                    }
                    service.importSnapshot("cc-b", "refseq", SourceFormat.JSON,
                            refseqB(), R_REFSEQ);
                }
            } catch (Throwable t) {
                failures.add(t);
            }
        });

        // readers: 6 threads compare the STABLE version and demand byte-identical
        // results; 3 threads compare the GROWING version and demand internal consistency
        for (int t = 0; t < 9; t++) {
            final boolean stable = t < 6;
            pool.submit(() -> {
                try {
                    start.await();
                    while (!stop.get()) {
                        if (stable) {
                            ResolutionComparison cmp = service.compareResolutions(
                                    "cc-a", R_REFSEQ, R_ENSEMBL);
                            assertEquals(baseline, cmp,
                                    "same data + same rule sets must always compare "
                                            + "identically, even under concurrency");
                            ResolutionComparison self = service.compareResolutions(
                                    "cc-a", R_REFSEQ, R_REFSEQ);
                            assertTrue(self.equivalent()
                                            && self.fieldDifferences().isEmpty()
                                            && self.clusterDifferences().isEmpty(),
                                    "self comparison must stay empty under concurrency");
                        } else {
                            ResolutionComparison cmp = service.compareResolutions(
                                    "cc-b", R_REFSEQ, R_ENSEMBL);
                            assertEquals("cc-b", cmp.snapshotVersion(),
                                    "comparison must be stamped with the exact version");
                            boolean anyDiff = !cmp.fieldDifferences().isEmpty()
                                    || !cmp.clusterDifferences().isEmpty()
                                    || !cmp.conflictsOnlyUnderA().isEmpty()
                                    || !cmp.conflictsOnlyUnderB().isEmpty()
                                    || !cmp.ambiguitiesOnlyUnderA().isEmpty()
                                    || !cmp.ambiguitiesOnlyUnderB().isEmpty();
                            assertEquals(anyDiff, !cmp.equivalent(),
                                    "equivalent flag must agree with the diff lists - "
                                            + "no half-computed comparison");
                            for (var d : cmp.fieldDifferences()) {
                                assertTrue(d.valueChanged() || d.winnerSourceChanged(),
                                        "every diff must say what changed: " + d);
                            }
                        }
                        comparisons.incrementAndGet();
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
                "no mixed/unstable comparison allowed: " + failures.stream().findFirst()
                        .map(Throwable::getMessage).orElse(""));

        // after the storm: the stable version still compares exactly like the baseline,
        // and twice in a row identically
        ResolutionComparison after1 = service.compareResolutions("cc-a", R_REFSEQ, R_ENSEMBL);
        ResolutionComparison after2 = service.compareResolutions("cc-a", R_REFSEQ, R_ENSEMBL);
        assertEquals(baseline, after1);
        assertEquals(after1, after2);
        log.info("post-storm comparison identical to baseline: fieldDiffs={}",
                after1.fieldDifferences().size());
    }
}
