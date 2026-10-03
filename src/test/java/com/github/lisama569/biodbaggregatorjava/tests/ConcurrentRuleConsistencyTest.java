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
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Concurrency with rule switching: while readers query, sources import and both data
 * versions and rule sets switch, every returned conclusion must be internally consistent
 * (one snapshot version AND one rule set end to end), and the same (version, rules) pair
 * always yields the same result regardless of execution order/interleaving.
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class ConcurrentRuleConsistencyTest {

    private static final Logger log = LoggerFactory.getLogger(ConcurrentRuleConsistencyTest.class);
    private static final String DEFAULT = "priority-rules-v1";
    private static final String ALT = "ensembl-first-v1";

    @Autowired
    private AggregatorService service;

    private static String refseqPayload(String prefix) {
        return "[{\"id\":\"NM1\",\"type\":\"gene\",\"symbol\":\"HBB\",\"name\":\"" + prefix
                + "-short\",\"organism\":\"Homo sapiens\",\"xrefs\":{\"uniprot\":\"Q1\"}}]";
    }

    private static final String UNIPROT_CSV =
            "id,type,symbol,name,sequence,organism,xrefs\n"
            + "Q1,gene,HBB,a clearly longer uniprot authoritative name,,Homo sapiens,refseq:NM1\n";

    @Test
    void concurrentQueriesImportsVersionSwitchAndRuleSwitchNeverMix() throws Exception {
        // publish two data versions, each with both sources
        for (String v : List.of("cv-a", "cv-b")) {
            service.importSnapshot(v, "refseq", SourceFormat.JSON,
                    refseqPayload(v.equals("cv-a") ? "aaa" : "bbb"));
            service.importSnapshot(v, "uniprot", SourceFormat.CSV, UNIPROT_CSV);
        }
        service.switchVersion("cv-a");

        ExecutorService pool = Executors.newFixedThreadPool(12);
        AtomicBoolean stop = new AtomicBoolean(false);
        AtomicInteger checks = new AtomicInteger();
        List<Throwable> failures = new ArrayList<>();
        List<Throwable> failureGuard = java.util.Collections.synchronizedList(failures);
        CountDownLatch start = new CountDownLatch(1);

        // writer 1: switch data versions
        pool.submit(() -> {
            try {
                start.await();
                while (!stop.get()) {
                    service.switchVersion("cv-b");
                    service.switchVersion("cv-a");
                }
            } catch (Throwable t) {
                failureGuard.add(t);
            }
        });
        // writer 2: switch rule sets
        pool.submit(() -> {
            try {
                start.await();
                while (!stop.get()) {
                    service.switchRules(ALT);
                    service.switchRules(DEFAULT);
                }
            } catch (Throwable t) {
                failureGuard.add(t);
            }
        });
        // writer 3: idempotent re-imports (force concurrent republish under locks)
        pool.submit(() -> {
            try {
                start.await();
                while (!stop.get()) {
                    service.importSnapshot("cv-a", "refseq", SourceFormat.JSON,
                            refseqPayload("aaa"));
                }
            } catch (Throwable t) {
                failureGuard.add(t);
            }
        });

        // readers: for every version/rules pair the result must be stable and self-consistent
        for (int t = 0; t < 9; t++) {
            pool.submit(() -> {
                try {
                    start.await();
                    while (!stop.get()) {
                        for (String version : List.of("cv-a", "cv-b")) {
                            for (String rules : List.of(DEFAULT, ALT)) {
                                ResolutionReport r1 =
                                        service.getResolution(version, rules).orElseThrow();
                                validateStamped(version, rules, r1);
                                ResolutionReport r2 =
                                        service.getResolution(version, rules).orElseThrow();
                                // same data + same rules => same conclusion, any order
                                assertEquals(r1, r2,
                                        "conclusion for (" + version + "," + rules
                                                + ") changed between reads (mixed rules?)");
                                // point lookup returns an entity stamped with the SAME pair
                                r1.entities().forEach(e -> {
                                    String id = e.identifiersBySource().values().iterator().next();
                                    ResolvedEntity point = service
                                            .resolveIdentifier(version, rules, id).orElseThrow();
                                    validateEntity(version, rules, point);
                                });
                                checks.incrementAndGet();
                            }
                        }
                    }
                } catch (Throwable ex) {
                    failureGuard.add(ex);
                }
            });
        }

        start.countDown();
        Thread.sleep(3000);
        stop.set(true);
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "threads did not finish");
        log.info("concurrent rule consistency: checks={} failures={}",
                checks.get(), failures.size());
        assertTrue(failures.isEmpty(),
                failures.stream().findFirst().map(Throwable::getMessage).orElse(""));
        assertTrue(checks.get() > 200, "expected substantial check volume, got " + checks.get());

        // cross-check determinism of the divergence itself under contention
        ResolvedEntity def = service.resolveIdentifier("cv-a", DEFAULT, "NM1").orElseThrow();
        ResolvedEntity alt = service.resolveIdentifier("cv-a", ALT, "Q1").orElseThrow();
        assertEquals("GENE:refseq:NM1", def.entityKey());
        assertEquals("GENE:uniprot:Q1", alt.entityKey());
        assertEquals(DEFAULT, def.rulesVersion());
        assertEquals(ALT, alt.rulesVersion());
        log.info("post-storm pinned/alt conclusions intact: defaultKey={} altKey={}",
                def.entityKey(), alt.entityKey());
    }

    private static void validateStamped(String version, String rules, ResolutionReport report) {
        assertEquals(version, report.snapshotVersion(), "report snapshot stamp mismatch");
        assertEquals(rules, report.rulesVersion(), "report rules stamp mismatch (mixed rules)");
        Set<String> entityVersions = new java.util.HashSet<>();
        Set<String> entityRules = new java.util.HashSet<>();
        for (ResolvedEntity e : report.entities()) {
            entityVersions.add(e.snapshotVersion());
            entityRules.add(e.rulesVersion());
            validateEntity(version, rules, e);
        }
        assertEquals(Set.of(version), entityVersions, "mixed snapshot versions in one report");
        assertEquals(Set.of(rules), entityRules, "mixed rule sets in one report");
    }

    private static void validateEntity(String version, String rules, ResolvedEntity e) {
        assertEquals(version, e.snapshotVersion(), "entity snapshot stamp mismatch");
        assertEquals(rules, e.rulesVersion(), "entity rules stamp mismatch (half-old half-new)");
        assertTrue(e.fieldWinnerSources().values().stream()
                        .allMatch(e.contributingSources()::contains),
                "field winner must be a contributing source");
    }
}
