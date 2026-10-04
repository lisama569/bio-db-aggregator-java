package com.github.lisama569.biodbaggregatorjava.resolution;

import com.github.lisama569.biodbaggregatorjava.model.EntityClusterDifference;
import com.github.lisama569.biodbaggregatorjava.model.FieldDecisionDifference;
import com.github.lisama569.biodbaggregatorjava.model.FieldDifferenceKind;
import com.github.lisama569.biodbaggregatorjava.model.ResolutionComparison;
import com.github.lisama569.biodbaggregatorjava.model.ResolutionConflict;
import com.github.lisama569.biodbaggregatorjava.model.ResolutionReport;
import com.github.lisama569.biodbaggregatorjava.model.ResolvedEntity;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

/**
 * Compares two {@link ResolutionReport}s of the SAME snapshot version resolved under two
 * different rule sets. All comparisons are signature based (member home tokens), so they
 * stay meaningful even when entity keys (priority anchors) change.
 */
@Component
public class ResolutionComparator {

    /** Stable cluster identity: sorted member home tokens {@code source:localId}. */
    public static String signatureOf(ResolvedEntity e) {
        TreeSet<String> tokens = new TreeSet<>();
        e.identifiersBySource().forEach((source, id) -> tokens.add(source + ":" + id));
        return String.join(",", tokens);
    }

    public ResolutionComparison compare(ResolutionReport a, ResolutionReport b) {
        Map<String, ResolvedEntity> byA = index(a);
        Map<String, ResolvedEntity> byB = index(b);

        List<EntityClusterDifference> clusterDiffs = new ArrayList<>();
        for (String sig : union(byA.keySet(), byB.keySet())) {
            ResolvedEntity ea = byA.get(sig);
            ResolvedEntity eb = byB.get(sig);
            if (ea == null || eb == null) {
                clusterDiffs.add(new EntityClusterDifference(sig,
                        ea == null ? null : ea.entityKey(),
                        eb == null ? null : eb.entityKey(),
                        ea == null ? List.of() : members(ea),
                        eb == null ? List.of() : members(eb)));
            }
        }

        List<FieldDecisionDifference> fieldDiffs = new ArrayList<>();
        for (String sig : byA.keySet()) {
            ResolvedEntity ea = byA.get(sig);
            ResolvedEntity eb = byB.get(sig);
            if (eb == null) {
                // cluster present only under A: already reported as a cluster difference
                continue;
            }
            addFieldDiff(fieldDiffs, sig, "symbol",
                    ea.canonicalSymbol(), winnerSourceOf(ea, "symbol"),
                    eb.canonicalSymbol(), winnerSourceOf(eb, "symbol"));
            addFieldDiff(fieldDiffs, sig, "name",
                    ea.canonicalName(), winnerSourceOf(ea, "name"),
                    eb.canonicalName(), winnerSourceOf(eb, "name"));
            addFieldDiff(fieldDiffs, sig, "sequence",
                    ea.sequence(), winnerSourceOf(ea, "sequence"),
                    eb.sequence(), winnerSourceOf(eb, "sequence"));
            addFieldDiff(fieldDiffs, sig, "organism",
                    ea.organism(), winnerSourceOf(ea, "organism"),
                    eb.organism(), winnerSourceOf(eb, "organism"));
        }

        List<String> conflictsA = describeConflicts(a);
        List<String> conflictsB = describeConflicts(b);
        List<String> ambA = describeAmbiguities(a);
        List<String> ambB = describeAmbiguities(b);

        List<String> conflictsOnlyA = minus(conflictsA, conflictsB);
        List<String> conflictsOnlyB = minus(conflictsB, conflictsA);
        List<String> ambOnlyA = minus(ambA, ambB);
        List<String> ambOnlyB = minus(ambB, ambA);

        boolean equivalent = clusterDiffs.isEmpty() && fieldDiffs.isEmpty()
                && conflictsOnlyA.isEmpty() && conflictsOnlyB.isEmpty()
                && ambOnlyA.isEmpty() && ambOnlyB.isEmpty();

        return new ResolutionComparison(
                a.snapshotVersion(), a.rulesVersion(), b.rulesVersion(),
                List.copyOf(clusterDiffs), List.copyOf(fieldDiffs),
                conflictsOnlyA, conflictsOnlyB, ambOnlyA, ambOnlyB, equivalent);
    }

    private static List<String> members(ResolvedEntity e) {
        return e.identifiersBySource().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(en -> en.getKey() + ":" + en.getValue())
                .toList();
    }

    private Map<String, ResolvedEntity> index(ResolutionReport report) {
        Map<String, ResolvedEntity> map = new LinkedHashMap<>();
        for (ResolvedEntity e : report.entities()) {
            map.put(signatureOf(e), e);
        }
        return map;
    }

    /**
     * Winning source of an adjudicated field: the resolver records it for every field that
     * has a non-blank winner (even for single contributors and unanimous values, where no
     * conflict object exists). Returns {@code null} only for a field with no contributor on
     * that side, which is exactly the "absent on one side" boundary the comparison must
     * neither miss nor hallucinate.
     */
    private static String winnerSourceOf(ResolvedEntity e, String field) {
        return e.fieldWinnerSources() == null ? null : e.fieldWinnerSources().get(field);
    }

    private static void addFieldDiff(List<FieldDecisionDifference> out, String sig, String field,
                                     String valueA, String sourceA,
                                     String valueB, String sourceB) {
        boolean valueChanged = !Objects.equals(valueA, valueB);
        boolean sourceChanged = !Objects.equals(sourceA, sourceB);
        if (!valueChanged && !sourceChanged) {
            // Same value AND same credited source: identical adjudication, never a diff.
            return;
        }
        FieldDifferenceKind kind = valueChanged && sourceChanged
                ? FieldDifferenceKind.VALUE_AND_SOURCE_CHANGED
                : valueChanged ? FieldDifferenceKind.VALUE_CHANGED
                : FieldDifferenceKind.WINNER_SOURCE_CHANGED;
        // A reported diff makes the overall comparison non-equivalent regardless of kind;
        // both sides' winning source and value are carried verbatim (null when absent).
        out.add(new FieldDecisionDifference(sig, field, kind, sourceA, valueA, sourceB, valueB));
    }

    private List<String> describeConflicts(ResolutionReport r) {
        List<String> out = new ArrayList<>();
        for (ResolvedEntity e : r.entities()) {
            String sig = signatureOf(e);
            for (ResolutionConflict c : e.fieldConflicts()) {
                out.add(sig + "|" + c.field() + "|winner=" + c.winnerSource()
                        + ":" + c.winnerValue() + "|losers="
                        + String.join(",", c.loserSources()));
            }
        }
        out.sort(String::compareTo);
        return out;
    }

    private List<String> describeAmbiguities(ResolutionReport r) {
        List<String> out = r.ambiguities().stream()
                .map(a -> a.token() + "|" + a.reason() + "|claimer="
                        + a.claimerSource() + ":" + a.claimerId())
                .sorted().toList();
        return new ArrayList<>(out);
    }

    private static TreeSet<String> union(java.util.Set<String> x, java.util.Set<String> y) {
        TreeSet<String> all = new TreeSet<>(x);
        all.addAll(y);
        return all;
    }

    private static List<String> minus(List<String> a, List<String> b) {
        return a.stream().filter(v -> !b.contains(v)).toList();
    }
}
