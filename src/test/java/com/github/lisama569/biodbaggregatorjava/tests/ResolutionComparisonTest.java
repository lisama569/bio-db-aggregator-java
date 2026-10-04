package com.github.lisama569.biodbaggregatorjava.tests;

import com.github.lisama569.biodbaggregatorjava.model.FieldDecisionDifference;
import com.github.lisama569.biodbaggregatorjava.model.ResolutionComparison;
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

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Field-decision comparison between two rule sets over the SAME snapshot:
 * <ul>
 *   <li>identical value but different winning source =&gt; reported, not equivalent;</li>
 *   <li>changed value =&gt; reported with both sides' winner source and value;</li>
 *   <li>unchanged value AND unchanged winner source =&gt; never reported;</li>
 *   <li>single-source entities, single-provider fields and absent fields are exact:</li>
 *   <li>a snapshot compared against itself under one rule set is fully equivalent;</li>
 *   <li>comparison sides are the very conclusions a direct per-rule-set query returns,
 *       and comparing never disturbs the version's pinned conclusion.</li>
 * </ul>
 *
 * <p>Scenario data (version {@code cmp-v1}):</p>
 * <ul>
 *   <li>cluster NM1/E1: symbol, name, sequence, organism all carry the SAME value in
 *       refseq and ensembl =&gt; only the signing source flips with the rule set;</li>
 *   <li>cluster NM3/E3: name differs (value change), symbol/sequence identical
 *       (source-only change), organism provided by refseq only (no diff possible);</li>
 *   <li>NM2: refseq-only entity, symbol only =&gt; no diff under any rule flip.</li>
 * </ul>
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class ResolutionComparisonTest {

    private static final Logger log = LoggerFactory.getLogger(ResolutionComparisonTest.class);

    private static final String RULES_A = "priority-rules-v1";          // refseq first
    private static final String RULES_B = "priority-rules-ensembl-v1";  // ensembl first
    private static final String VERSION = "cmp-v1";

    private static final String REFSEQ = """
            [
              {"id":"NM1","type":"gene","symbol":"HBB","name":"shared name",
               "sequence":"ACGT","organism":"Homo sapiens","xrefs":{"ensembl":"E1"}},
              {"id":"NM3","type":"gene","symbol":"BRCA1","name":"refseq distinct name",
               "sequence":"MKTAA","organism":"Homo sapiens","xrefs":{"ensembl":"E3"}},
              {"id":"NM2","type":"gene","symbol":"TP53"}
            ]
            """;

    private static final String ENSEMBL = """
            REC|E1|gene|HBB|shared name|ACGT|Homo sapiens|refseq:NM1
            REC|E3|gene|BRCA1|ensembl distinct name|MKTAA||refseq:NM3
            """;

    private void seed() {
        service.importSnapshot(VERSION, "refseq", SourceFormat.JSON, REFSEQ, RULES_A);
        service.importSnapshot(VERSION, "ensembl", SourceFormat.FLAT, ENSEMBL, RULES_A);
    }

    @Autowired
    private AggregatorService service;

    private static String sigOf(ResolutionReport r, String entityKeyPrefix) {
        return r.entities().stream()
                .filter(e -> e.entityKey().contains(entityKeyPrefix))
                .map(e -> {
                    java.util.TreeSet<String> t = new java.util.TreeSet<>();
                    e.identifiersBySource().forEach((s, id) -> t.add(s + ":" + id));
                    return String.join(",", t);
                })
                .findFirst().orElseThrow();
    }

    private static Optional<FieldDecisionDifference> diffFor(ResolutionComparison c,
                                                             String sig, String field) {
        return c.fieldDifferences().stream()
                .filter(d -> d.signature().equals(sig) && d.field().equals(field))
                .findFirst();
    }

    @Test
    void sameValueDifferentWinnerSourceIsReportedForEveryAdjudicatedField() {
        seed();
        ResolutionReport sideA = service.getResolution(VERSION, RULES_A);
        ResolutionReport sideB = service.getResolution(VERSION, RULES_B);
        String sig1 = sigOf(sideA, "NM1");
        String sig3 = sigOf(sideA, "NM3");
        String sig2 = sigOf(sideA, "NM2");
        log.info("scenario: version={} rulesA={} rulesB={} clusters: NM1/E1={} NM3/E3={} NM2={}",
                VERSION, RULES_A, RULES_B, sig1, sig3, sig2);
        for (ResolvedEntity e : sideA.entities()) {
            log.info("side A ({}) entity={} fieldWinners={}", RULES_A, e.entityKey(),
                    e.fieldWinners());
        }
        for (ResolvedEntity e : sideB.entities()) {
            log.info("side B ({}) entity={} fieldWinners={}", RULES_B, e.entityKey(),
                    e.fieldWinners());
        }

        ResolutionComparison cmp = service.compareResolutions(VERSION, RULES_A, RULES_B);
        assertFalse(cmp.equivalent(),
                "winner-source flips with identical values must break equivalence");
        assertTrue(cmp.clusterDifferences().isEmpty(),
                "same snapshot merges identically under both rule sets");

        // --- cluster NM1/E1: all four fields keep their value, only the signer flips
        for (String field : List.of("symbol", "name", "sequence", "organism")) {
            FieldDecisionDifference d = diffFor(cmp, sig1, field).orElseThrow(() ->
                    new AssertionError("missing source-only diff for " + field + " on " + sig1));
            assertFalse(d.valueChanged(), field + ": value identical on both sides");
            assertTrue(d.winnerSourceChanged(), field + ": winner source must flip");
            assertEquals("refseq", d.winnerSourceA());
            assertEquals("ensembl", d.winnerSourceB());
            assertEquals(d.winnerValueA(), d.winnerValueB(),
                    field + ": reported values must be the shared identical value");
            log.info("source-only diff confirmed: field={} A[{}='{}'] B[{}='{}']",
                    field, d.winnerSourceA(), d.winnerValueA(),
                    d.winnerSourceB(), d.winnerValueB());
        }

        // --- cluster NM3/E3: name value changes; symbol/sequence source-only;
        //     organism is provided by refseq alone -> winner refseq under BOTH rule sets
        FieldDecisionDifference name = diffFor(cmp, sig3, "name").orElseThrow();
        assertTrue(name.valueChanged());
        assertTrue(name.winnerSourceChanged());
        assertEquals("refseq", name.winnerSourceA());
        assertEquals("refseq distinct name", name.winnerValueA());
        assertEquals("ensembl", name.winnerSourceB());
        assertEquals("ensembl distinct name", name.winnerValueB());
        log.info("value diff confirmed: name A[{}='{}'] B[{}='{}']",
                name.winnerSourceA(), name.winnerValueA(),
                name.winnerSourceB(), name.winnerValueB());

        for (String field : List.of("symbol", "sequence")) {
            FieldDecisionDifference d = diffFor(cmp, sig3, field).orElseThrow(() ->
                    new AssertionError("missing source-only diff for " + field));
            assertFalse(d.valueChanged());
            assertTrue(d.winnerSourceChanged());
        }
        assertTrue(diffFor(cmp, sig3, "organism").isEmpty(),
                "single-provider field: refseq wins organism under both rule sets, no diff");

        // --- NM2: single-source entity, no field may appear as a difference
        assertTrue(cmp.fieldDifferences().stream()
                        .noneMatch(d -> d.signature().equals(sig2)),
                "single-source entity must not produce phantom diffs");

        // no field diff may carry a null-vs-null or same-source/same-value pair
        for (FieldDecisionDifference d : cmp.fieldDifferences()) {
            assertTrue(d.valueChanged() || d.winnerSourceChanged(),
                    "every reported diff must say what changed: " + d);
        }
    }

    @Test
    void unchangedValueAndSourceAreNeverReportedAndSelfComparisonIsEquivalent() {
        seed();
        ResolutionComparison self = service.compareResolutions(VERSION, RULES_A, RULES_A);
        assertTrue(self.equivalent());
        assertTrue(self.fieldDifferences().isEmpty());
        assertTrue(self.clusterDifferences().isEmpty());
        assertTrue(self.conflictsOnlyUnderA().isEmpty());
        assertTrue(self.conflictsOnlyUnderB().isEmpty());
        assertTrue(self.ambiguitiesOnlyUnderA().isEmpty());
        assertTrue(self.ambiguitiesOnlyUnderB().isEmpty());
        log.info("self comparison: version={} rules={} equivalent={} (no phantom diffs)",
                VERSION, RULES_A, self.equivalent());

        // repeated identical comparisons are byte-identical
        ResolutionComparison again = service.compareResolutions(VERSION, RULES_A, RULES_A);
        assertEquals(self, again, "same data + same rule sets => identical comparison");
    }

    @Test
    void comparisonSidesMatchDirectRuleSetQueriesAndPinnedConclusionIsUntouched() {
        seed();
        ResolutionReport pinnedBefore = service.getResolution(VERSION).orElseThrow();
        ResolutionReport directA = service.getResolution(VERSION, RULES_A);
        ResolutionReport directB = service.getResolution(VERSION, RULES_B);

        ResolutionComparison cmp = service.compareResolutions(VERSION, RULES_A, RULES_B);

        // every reported side value/source must equal what a direct query under that
        // rule set concludes for the same cluster
        for (FieldDecisionDifference d : cmp.fieldDifferences()) {
            ResolvedEntity ea = directA.entities().stream()
                    .filter(e -> signatureOf(e).equals(d.signature())).findFirst().orElseThrow();
            ResolvedEntity eb = directB.entities().stream()
                    .filter(e -> signatureOf(e).equals(d.signature())).findFirst().orElseThrow();
            assertEquals(ea.fieldWinner(d.field()), d.winnerSourceA(),
                    "side A source must match direct " + RULES_A + " query for " + d.field());
            assertEquals(fieldValue(ea, d.field()), d.winnerValueA());
            assertEquals(eb.fieldWinner(d.field()), d.winnerSourceB(),
                    "side B source must match direct " + RULES_B + " query for " + d.field());
            assertEquals(fieldValue(eb, d.field()), d.winnerValueB());
        }

        // comparing must not rewrite the version's pinned (historical) conclusion
        ResolutionReport pinnedAfter = service.getResolution(VERSION).orElseThrow();
        assertEquals(pinnedBefore, pinnedAfter);
        assertEquals(RULES_A, pinnedAfter.rulesVersion());
        log.info("pinned conclusion stable across comparison: version={} pinnedRules={}",
                VERSION, pinnedAfter.rulesVersion());
    }

    private static String signatureOf(ResolvedEntity e) {
        java.util.TreeSet<String> t = new java.util.TreeSet<>();
        e.identifiersBySource().forEach((s, id) -> t.add(s + ":" + id));
        return String.join(",", t);
    }

    private static String fieldValue(ResolvedEntity e, String field) {
        return switch (field) {
            case "symbol" -> e.canonicalSymbol();
            case "name" -> e.canonicalName();
            case "sequence" -> e.sequence();
            case "organism" -> e.organism();
            default -> throw new IllegalArgumentException(field);
        };
    }
}
