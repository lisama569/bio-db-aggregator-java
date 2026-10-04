package com.github.lisama569.biodbaggregatorjava.tests;

import com.github.lisama569.biodbaggregatorjava.model.EntityType;
import com.github.lisama569.biodbaggregatorjava.model.FieldDecisionDifference;
import com.github.lisama569.biodbaggregatorjava.model.FieldDifferenceKind;
import com.github.lisama569.biodbaggregatorjava.model.ResolutionComparison;
import com.github.lisama569.biodbaggregatorjava.model.ResolutionReport;
import com.github.lisama569.biodbaggregatorjava.model.ResolvedEntity;
import com.github.lisama569.biodbaggregatorjava.resolution.ResolutionComparator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Business coverage of the two-rule-set comparison boundary semantics, in isolation:
 * <ol>
 *   <li>same value, winner source changed - reported for symbol/name/sequence/organism;</li>
 *   <li>only the value changed (same credited source) - VALUE_CHANGED;</li>
 *   <li>value and source both changed - VALUE_AND_SOURCE_CHANGED;</li>
 *   <li>identical value AND source - never reported; same report vs itself = no difference;</li>
 *   <li>single-source field present identically on both sides - no false positive;</li>
 *   <li>field with a value on only one side - reported once, no phantom source change;</li>
 *   <li>cluster present on only one side - reported as a cluster difference.</li>
 * </ol>
 */
class ResolutionComparatorTest {

    private static final Logger log = LoggerFactory.getLogger(ResolutionComparatorTest.class);
    private static final List<String> FIELDS = List.of("symbol", "name", "sequence", "organism");

    private final ResolutionComparator comparator = new ResolutionComparator();

    /** Build an entity whose signature is derived from its member home tokens. */
    private static ResolvedEntity entity(String rules, Map<String, String> members,
                                        Map<String, String> winnerSources,
                                        Map<String, String> values) {
        String key = "GENE:" + members.keySet().stream().sorted().findFirst().orElseThrow()
                + ":" + members.values().stream().sorted().findFirst().orElseThrow();
        return new ResolvedEntity(
                key, EntityType.GENE,
                values.get("symbol"), values.get("name"),
                values.get("sequence"), values.get("organism"),
                Map.copyOf(members), List.of(),
                List.copyOf(members.keySet()),
                Map.copyOf(winnerSources),
                "snap-1", rules);
    }

    private static ResolutionReport report(String rules, ResolvedEntity... entities) {
        return new ResolutionReport("snap-1", rules, List.of(entities), 0, List.of());
    }

    private static Map<String, String> winners(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    private static Map<String, String> members(String... kv) {
        return winners(kv);
    }

    private static Map<String, String> values(String... kv) {
        return winners(kv);
    }

    private FieldDecisionDifference one(ResolutionComparison c, String field) {
        List<FieldDecisionDifference> found = c.fieldDifferences().stream()
                .filter(d -> d.field().equals(field)).toList();
        assertEquals(1, found.size(), "exactly one diff for " + field + ": " + found);
        return found.get(0);
    }

    private void logCase(String business, ResolutionComparison c) {
        log.info("[business={}] snapshot={} rulesA={} rulesB={} equivalent={} fieldDiffs={}",
                business, c.snapshotVersion(), c.rulesSetA(), c.rulesSetB(),
                c.equivalent(), c.fieldDifferences().size());
        for (FieldDecisionDifference d : c.fieldDifferences()) {
            log.info("[business={}] field={} kind={} A(winner={}, value='{}') B(winner={}, value='{}')",
                    business, d.field(), d.kind(),
                    d.winnerSourceA(), d.winnerValueA(),
                    d.winnerSourceB(), d.winnerValueB());
        }
    }

    @Test
    @DisplayName("same winning value but winner source changed: all four fields reported")
    void sameValueDifferentWinnerSourceIsReportedForEveryField() {
        // both sources provide byte-identical values for all four fields; rule set A credits
        // refseq, rule set B credits uniprot for each field.
        Map<String, String> members = members("refseq", "R1", "uniprot", "U1");
        Map<String, String> vals = values(
                "symbol", "HBB", "name", "shared name",
                "sequence", "MVL", "organism", "Homo sapiens");
        Map<String, String> winA = winners(
                "symbol", "refseq", "name", "refseq",
                "sequence", "refseq", "organism", "refseq");
        Map<String, String> winB = winners(
                "symbol", "uniprot", "name", "uniprot",
                "sequence", "uniprot", "organism", "uniprot");

        ResolutionComparison c = comparator.compare(
                report("rules-A", entity("rules-A", members, winA, vals)),
                report("rules-B", entity("rules-B", members, winB, vals)));
        logCase("same-value-source-flip", c);

        assertFalse(c.equivalent(), "attribution change must count as a real difference");
        assertEquals(4, c.fieldDifferences().size());
        for (String f : FIELDS) {
            FieldDecisionDifference d = one(c, f);
            assertEquals(FieldDifferenceKind.WINNER_SOURCE_CHANGED, d.kind());
            assertEquals("refseq", d.winnerSourceA());
            assertEquals("uniprot", d.winnerSourceB());
            assertEquals(d.winnerValueA(), d.winnerValueB());
        }
    }

    @Test
    @DisplayName("only value changed, source unchanged: VALUE_CHANGED")
    void onlyValueChangedIsReportedAsValueChanged() {
        Map<String, String> members = members("refseq", "R1", "uniprot", "U1");
        Map<String, String> win = winners("name", "refseq");
        ResolutionComparison c = comparator.compare(
                report("rules-A", entity("rules-A", members, win, values("name", "old"))),
                report("rules-B", entity("rules-B", members, win, values("name", "new"))));
        logCase("value-only-change", c);

        assertFalse(c.equivalent());
        FieldDecisionDifference d = one(c, "name");
        assertEquals(FieldDifferenceKind.VALUE_CHANGED, d.kind());
        assertEquals("refseq", d.winnerSourceA());
        assertEquals("refseq", d.winnerSourceB());
        assertEquals("old", d.winnerValueA());
        assertEquals("new", d.winnerValueB());
    }

    @Test
    @DisplayName("value and winner source both changed: VALUE_AND_SOURCE_CHANGED")
    void valueAndSourceBothChanged() {
        Map<String, String> members = members("refseq", "R1", "uniprot", "U1");
        ResolutionComparison c = comparator.compare(
                report("rules-A",
                        entity("rules-A", members, winners("sequence", "refseq"),
                                values("sequence", "AAA"))),
                report("rules-B",
                        entity("rules-B", members, winners("sequence", "uniprot"),
                                values("sequence", "BBB"))));
        logCase("value-and-source-change", c);

        FieldDecisionDifference d = one(c, "sequence");
        assertEquals(FieldDifferenceKind.VALUE_AND_SOURCE_CHANGED, d.kind());
        assertEquals("AAA", d.winnerValueA());
        assertEquals("BBB", d.winnerValueB());
    }

    @Test
    @DisplayName("same value and same source: no diff; same report compared to itself: no diff")
    void identicalAdjudicationIsNoDifference() {
        Map<String, String> members = members("refseq", "R1", "uniprot", "U1");
        Map<String, String> vals = values(
                "symbol", "HBB", "name", "shared", "sequence", "MVL", "organism", "org");
        Map<String, String> win = winners(
                "symbol", "refseq", "name", "refseq",
                "sequence", "refseq", "organism", "refseq");
        ResolutionReport a = report("rules-A", entity("rules-A", members, win, vals));

        ResolutionComparison twoEqualReports = comparator.compare(
                a, report("rules-A", entity("rules-A", members, win, vals)));
        logCase("identical-reports", twoEqualReports);
        assertTrue(twoEqualReports.fieldDifferences().isEmpty());
        assertTrue(twoEqualReports.equivalent());

        // same data with the SAME rule set against itself must always be "no difference"
        ResolutionComparison self = comparator.compare(a, a);
        assertTrue(self.equivalent());
        assertTrue(self.fieldDifferences().isEmpty());
        assertTrue(self.clusterDifferences().isEmpty());
    }

    @Test
    @DisplayName("single-source field identical on both sides is never a false positive")
    void singleSourceFieldDoesNotFalselyDiff() {
        // only refseq contributes a sequence; under both rule sets refseq is the unique
        // winner with the same value -> nothing to report, even though another source is
        // a member of the cluster (it simply has no value for that field).
        Map<String, String> members = members("refseq", "R1", "uniprot", "U1");
        Map<String, String> vals = values("symbol", "HBB", "sequence", "MVL");
        Map<String, String> winA = winners("symbol", "refseq", "sequence", "refseq");
        Map<String, String> winB = winners("symbol", "uniprot", "sequence", "refseq");
        ResolutionComparison c = comparator.compare(
                report("rules-A", entity("rules-A", members, winA, vals)),
                report("rules-B", entity("rules-B", members, winB, vals)));
        logCase("single-source-field", c);

        // symbol attribution flips (both sources have it)...
        assertEquals(FieldDifferenceKind.WINNER_SOURCE_CHANGED, one(c, "symbol").kind());
        // ...but sequence, with refseq as its sole contributor on both sides, is untouched.
        assertTrue(c.fieldDifferences().stream().noneMatch(d -> d.field().equals("sequence")),
                "single-source field must not be reported: " + c.fieldDifferences());
    }

    @Test
    @DisplayName("field with a value on only one side: reported, value dimension, no phantom source")
    void fieldAbsentOnOneSideIsReportedExactlyOnce() {
        // Same cluster both sides; organism only exists on side A. Side B simulates a rule
        // set whose per-field override still credits refseq for symbol, so the shared symbol
        // is identical in BOTH value and source and must not be reported alongside.
        Map<String, String> members = members("refseq", "R1", "uniprot", "U1");
        ResolutionComparison c = comparator.compare(
                report("rules-A",
                        entity("rules-A", members,
                                winners("symbol", "refseq", "organism", "refseq"),
                                values("symbol", "HBB", "organism", "Homo sapiens"))),
                report("rules-B",
                        entity("rules-B", members, winners("symbol", "refseq"),
                                values("symbol", "HBB"))));
        logCase("field-missing-on-one-side", c);

        assertFalse(c.equivalent());
        FieldDecisionDifference d = one(c, "organism");
        // value present on A, absent on B: value and source both go from something to
        // nothing - reported once, with nulls on the absent side (no phantom attribution).
        assertEquals(FieldDifferenceKind.VALUE_AND_SOURCE_CHANGED, d.kind());
        assertEquals("refseq", d.winnerSourceA());
        assertEquals("Homo sapiens", d.winnerValueA());
        assertEquals(null, d.winnerSourceB(), "no winner may be invented for the absent side");
        assertEquals(null, d.winnerValueB());
        // the field both sides agree on (same value+source) is not reported
        assertTrue(c.fieldDifferences().stream().noneMatch(d2 -> d2.field().equals("symbol")));
    }

    @Test
    @DisplayName("cluster present only under one side: cluster difference, no field scanning")
    void clusterOnlyOnOneSideIsReported() {
        Map<String, String> members = members("refseq", "R1");
        Map<String, String> vals = values("symbol", "HBB");
        Map<String, String> win = winners("symbol", "refseq");
        ResolutionComparison c = comparator.compare(
                report("rules-A", entity("rules-A", members, win, vals)),
                report("rules-B"));
        logCase("cluster-only-on-one-side", c);

        assertFalse(c.equivalent());
        assertEquals(1, c.clusterDifferences().size());
        assertTrue(c.fieldDifferences().isEmpty(),
                "fields of a one-sided cluster are covered by the cluster diff, not field diffs");
    }
}
