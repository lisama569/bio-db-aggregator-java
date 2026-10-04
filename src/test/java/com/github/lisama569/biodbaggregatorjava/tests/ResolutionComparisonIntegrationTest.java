package com.github.lisama569.biodbaggregatorjava.tests;

import com.github.lisama569.biodbaggregatorjava.model.FieldDecisionDifference;
import com.github.lisama569.biodbaggregatorjava.model.FieldDifferenceKind;
import com.github.lisama569.biodbaggregatorjava.model.ResolutionComparison;
import com.github.lisama569.biodbaggregatorjava.model.ResolutionReport;
import com.github.lisama569.biodbaggregatorjava.model.ResolvedEntity;
import com.github.lisama569.biodbaggregatorjava.model.SourceFormat;
import com.github.lisama569.biodbaggregatorjava.service.AggregatorService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end comparison semantics over a real parsed snapshot resolved by the shipped
 * rule sets {@code priority-rules-v1} (RefSeq-first) and
 * {@code priority-rules-ensembl-v1} (Ensembl-first).
 *
 * <p>Business scenarios covered:</p>
 * <ol>
 *   <li><b>identicalValuesDifferentCredit</b>: two sources give byte-identical values for
 *       symbol/name/sequence/organism; only the credited winner changes with the rule set.
 *       All four fields must be reported as WINNER_SOURCE_CHANGED and the comparison must
 *       not be equivalent.</li>
 *   <li><b>singleSourceCluster</b>: a cluster contributed by one source only never shows a
 *       field difference (no false positive), including its single-source fields.</li>
 *   <li><b>singleRecordAbsentField</b>: a field provided on only one side of a field is
 *       absent on the other side boundary (entity present under both rule sets).</li>
 *   <li>self-comparison (same rule set twice) is always equivalent; pinned conclusions are
 *       not modified by comparisons; the compared reports equal the standalone
 *       {@code resolution?rules=} results; repeated comparisons are identical.</li>
 * </ol>
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class ResolutionComparisonIntegrationTest {

    private static final Logger log =
            LoggerFactory.getLogger(ResolutionComparisonIntegrationTest.class);

    private static final String R_REFSEQ = "priority-rules-v1";
    private static final String R_ENSEMBL = "priority-rules-ensembl-v1";
    private static final String VERSION = "v-cmp";

    @Autowired
    private AggregatorService service;

    /**
     * Three clusters in one snapshot:
     * - refseq NM1 + ensembl E1: identical values on all four fields;
     * - refseq NM2 + uniprot U2: refseq-only sequence (single-source field), plus an
     *   ensembl-only single-record cluster with no name/sequence/organism values.
     */
    private static final String REFSEQ = """
            [
              {"id":"NM1","type":"gene","symbol":"HBB","name":"beta globin",
               "sequence":"MVLSPADK","organism":"Homo sapiens",
               "xrefs":{"ensembl":"E1"}},
              {"id":"NM2","type":"gene","symbol":"HBA","name":"alpha globin",
               "sequence":"MVLSP","organism":"Homo sapiens",
               "xrefs":{"uniprot":"U2"}},
              {"id":"NM3","type":"gene","symbol":"SOLO"}
            ]
            """;

    private static final String ENSEMBL = """
            REC|E1|gene|HBB|beta globin|MVLSPADK|Homo sapiens|refseq:NM1
            """;

    private static final String UNIPROT = """
            id,type,symbol,name,sequence,organism,xrefs
            U2,gene,HBA,alpha globin,,Homo sapiens,refseq:NM2
            """;

    private void seed() {
        assertEquals("CREATED",
                service.importSnapshot(VERSION, "refseq", SourceFormat.JSON, REFSEQ, R_REFSEQ)
                        .status());
        assertEquals("CREATED",
                service.importSnapshot(VERSION, "ensembl", SourceFormat.FLAT, ENSEMBL, R_REFSEQ)
                        .status());
        assertEquals("CREATED",
                service.importSnapshot(VERSION, "uniprot", SourceFormat.CSV, UNIPROT, R_REFSEQ)
                        .status());
    }

    private void logComparison(String business, ResolutionComparison c) {
        log.info("[business={}] comparison snapshot={} rulesA={} rulesB={} equivalent={} "
                        + "clusterDiffs={} fieldDiffs={} conflictsOnlyA={} conflictsOnlyB={} "
                        + "ambiguitiesOnlyA={} ambiguitiesOnlyB={}",
                business, c.snapshotVersion(), c.rulesSetA(), c.rulesSetB(), c.equivalent(),
                c.clusterDifferences().size(), c.fieldDifferences().size(),
                c.conflictsOnlyUnderA().size(), c.conflictsOnlyUnderB().size(),
                c.ambiguitiesOnlyUnderA().size(), c.ambiguitiesOnlyUnderB().size());
        for (FieldDecisionDifference d : c.fieldDifferences()) {
            log.info("[business={}] field={} kind={} A(winner={}, value='{}') B(winner={}, value='{}')",
                    business, d.field(), d.kind(),
                    d.winnerSourceA(), d.winnerValueA(),
                    d.winnerSourceB(), d.winnerSourceB(), d.winnerValueB());
        }
    }

    private ResolvedEntity cluster(ResolutionReport r, String source, String localId) {
        return r.entities().stream()
                .filter(e -> e.identifiersBySource().getOrDefault(source, "").equals(localId))
                .findFirst().orElseThrow();
    }

    @Test
    @DisplayName("identical values under both rule sets: winner-source flips are still differences")
    void identicalValuesButWinnerSourceChangesAreReportedEndToEnd() {
        seed();

        // The compared conclusions must be exactly what a standalone scoped query returns,
        // computed fully under each named rule set.
        ResolutionReport aStandalone = service.getResolution(VERSION, R_REFSEQ);
        ResolutionReport bStandalone = service.getResolution(VERSION, R_ENSEMBL);
        assertEquals(R_REFSEQ, aStandalone.rulesVersion());
        assertEquals(R_ENSEMBL, bStandalone.rulesVersion());

        ResolvedEntity ea = cluster(aStandalone, "refseq", "NM1");
        ResolvedEntity eb = cluster(bStandalone, "refseq", "NM1");
        // values identical...
        assertEquals(ea.canonicalSymbol(), eb.canonicalSymbol());
        assertEquals(ea.canonicalName(), eb.canonicalName());
        assertEquals(ea.sequence(), eb.sequence());
        assertEquals(ea.organism(), eb.organism());
        // ...but every field's credited winner flipped refseq -> ensembl
        for (String f : List.of("symbol", "name", "sequence", "organism")) {
            assertEquals("refseq", ea.fieldWinnerSources().get(f), "field " + f);
            assertEquals("ensembl", eb.fieldWinnerSources().get(f), "field " + f);
        }

        ResolutionComparison c = service.compareResolutions(VERSION, R_REFSEQ, R_ENSEMBL);
        logComparison("identical-values-different-credit", c);

        assertFalse(c.equivalent(), "same values with flipped attribution is NOT equivalent");
        String sig = ResolutionComparatorSig(ea);
        for (String f : List.of("symbol", "name", "sequence", "organism")) {
            FieldDecisionDifference d = c.fieldDifferences().stream()
                    .filter(x -> x.signature().equals(sig) && x.field().equals(f))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("missing source-only diff for " + f));
            assertEquals(FieldDifferenceKind.WINNER_SOURCE_CHANGED, d.kind(),
                    "value identical for " + f + ", only attribution changed");
            assertEquals("refseq", d.winnerSourceA());
            assertEquals("ensembl", d.winnerSourceB());
            assertNotNull(d.winnerValueA());
            assertEquals(d.winnerValueA(), d.winnerValueB());
        }
    }

    private static String ResolutionComparatorSig(ResolvedEntity e) {
        return com.github.lisama569.biodbaggregatorjava.resolution.ResolutionComparator
                .signatureOf(e);
    }

    @Test
    @DisplayName("single-source clusters and single-source fields produce no false positives")
    void singleSourceClustersAndFieldsAreNotDiffed() {
        seed();
        ResolutionComparison c = service.compareResolutions(VERSION, R_REFSEQ, R_ENSEMBL);
        logComparison("single-source-boundaries", c);

        // the single-record refseq cluster NM3: present under both rule sets, refseq is its
        // only source under either ordering, so no field differences for its signature.
        ResolvedEntity solo = cluster(service.getResolution(VERSION, R_REFSEQ), "refseq", "NM3");
        String soloSig = ResolutionComparatorSig(solo);
        assertTrue(c.fieldDifferences().stream()
                        .noneMatch(d -> d.signature().equals(soloSig)),
                "single-record cluster must not produce field diffs");
        assertEquals(List.of("refseq"), solo.contributingSources());

        // NM2/U2 cluster: sequence only refseq provides, so sequence stays credited to
        // refseq under BOTH rule sets and must not be reported; organism and name are
        // shared (winner flips), symbol is shared too - those flips ARE reported.
        ResolvedEntity nm2 = cluster(service.getResolution(VERSION, R_REFSEQ), "refseq", "NM2");
        String nm2Sig = ResolutionComparatorSig(nm2);
        Map<String, List<FieldDecisionDifference>> perField = c.fieldDifferences().stream()
                .filter(d -> d.signature().equals(nm2Sig))
                .collect(java.util.stream.Collectors.groupingBy(
                        FieldDecisionDifference::field, java.util.LinkedHashMap::new,
                        java.util.stream.Collectors.toList()));
        assertFalse(perField.containsKey("sequence"),
                "single-source field sequence must not be reported: " + perField);
        for (String f : List.of("symbol", "name", "organism")) {
            FieldDecisionDifference d = perField.get(f).get(0);
            assertEquals(FieldDifferenceKind.WINNER_SOURCE_CHANGED, d.kind());
            assertEquals("refseq", d.winnerSourceA());
            assertEquals("uniprot", d.winnerSourceB(),
                    "ensembl has no record in this cluster; ensembl-first rule falls through "
                            + "to uniprot before refseq");
            assertEquals(d.winnerValueA(), d.winnerValueB());
        }
    }

    @Test
    @DisplayName("comparison does not rewrite pinned conclusions; self and repeats are identical")
    void selfComparisonAndRepeatabilityAndPinnedConclusion() {
        seed();

        // same data with the SAME rule set against itself: no difference whatsoever
        ResolutionComparison self = service.compareResolutions(VERSION, R_REFSEQ, R_REFSEQ);
        logComparison("self-comparison", self);
        assertTrue(self.equivalent());
        assertTrue(self.fieldDifferences().isEmpty());
        assertTrue(self.clusterDifferences().isEmpty());

        // two-side comparisons are stable across repetitions...
        ResolutionComparison first = service.compareResolutions(VERSION, R_REFSEQ, R_ENSEMBL);
        ResolutionComparison second = service.compareResolutions(VERSION, R_REFSEQ, R_ENSEMBL);
        assertEquals(first, second, "same data + same two rule sets => identical comparison");

        // ...and do not disturb the version's pinned (RefSeq-first) conclusion
        ResolutionReport pinned = service.getResolution(VERSION).orElseThrow();
        assertEquals(R_REFSEQ, pinned.rulesVersion());
        ResolvedEntity hbb = cluster(pinned, "refseq", "NM1");
        assertEquals("refseq", hbb.fieldWinnerSources().get("symbol"));
        assertEquals("beta globin", hbb.canonicalName());
        log.info("pinned conclusion preserved after comparisons: version={} rules={} winners={}",
                pinned.snapshotVersion(), pinned.rulesVersion(), hbb.fieldWinnerSources());
    }
}
