package com.github.lisama569.biodbaggregatorjava.resolution;

import com.github.lisama569.biodbaggregatorjava.model.EntityClusterDifference;
import com.github.lisama569.biodbaggregatorjava.model.FieldDecisionDifference;
import com.github.lisama569.biodbaggregatorjava.model.ResolutionComparison;
import com.github.lisama569.biodbaggregatorjava.model.ResolutionConflict;
import com.github.lisama569.biodbaggregatorjava.model.ResolutionReport;
import com.github.lisama569.biodbaggregatorjava.model.ResolvedEntity;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
            ResolvedEntity eb = byB.get(sig);
            if (eb == null) {
                continue;
            }
            ResolvedEntity ea = byA.get(sig);
            addFieldDiff(fieldDiffs, sig, "symbol",
                    ea.canonicalSymbol(), winnerOf(ea, "symbol"),
                    eb.canonicalSymbol(), winnerOf(eb, "symbol"));
            addFieldDiff(fieldDiffs, sig, "name",
                    ea.canonicalName(), winnerOf(ea, "name"),
                    eb.canonicalName(), winnerOf(eb, "name"));
            addFieldDiff(fieldDiffs, sig, "sequence",
                    ea.sequence(), winnerOf(ea, "sequence"),
                    eb.sequence(), winnerOf(eb, "sequence"));
            addFieldDiff(fieldDiffs, sig, "organism",
                    ea.organism(), winnerOf(ea, "organism"),
                    eb.organism(), winnerOf(eb, "organism"));
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

    private static String winnerOf(ResolvedEntity e, String field) {
        return e.fieldConflicts().stream()
                .filter(c -> c.field().equals(field))
                .map(ResolutionConflict::winnerSource)
                .findFirst()
                .orElseGet(() -> {
                    // No conflict recorded: the winner is the entity's only contributor for
                    // that field (or the highest-priority one). Derive from identifiers is
                    // impossible without raw members, so return the contributing source when
                    // exactly one; otherwise null (value equality still drives the diff).
                    return null;
                });
    }

    private static void addFieldDiff(List<FieldDecisionDifference> out, String sig, String field,
                                     String valueA, String sourceA,
                                     String valueB, String sourceB) {
        boolean changed = valueA == null ? valueB != null : !valueA.equals(valueB);
        if (changed || (sourceA != null && !sourceA.equals(sourceB))) {
            if (changed || sourceB != null) {
                out.add(new FieldDecisionDifference(sig, field, sourceA, valueA, sourceB, valueB));
            }
        }
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
