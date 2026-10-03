package com.github.lisama569.biodbaggregatorjava.model.rules;

import com.github.lisama569.biodbaggregatorjava.model.AmbiguousLink;
import com.github.lisama569.biodbaggregatorjava.model.ResolvedEntity;

import java.util.List;

/**
 * Field-by-field comparison of one data snapshot resolved under two distinct rule sets.
 *
 * @param snapshotVersion          data snapshot both reports were computed from
 * @param rulesIdA                 first rule set id
 * @param rulesIdB                 second rule set id
 * @param identical                true when entity set, field winners, conflicts and
 *                                 ambiguities are all equal
 * @param entityCountA             number of merged entities under rules A
 * @param entityCountB             number of merged entities under rules B
 * @param clusterDifferences       per-cluster divergences (stable member-based matching)
 * @param ambiguousOnlyInA         ambiguity tokens present only under rules A
 * @param ambiguousOnlyInB         ambiguity tokens present only under rules B
 * @param totalFieldWinnerChanges  number of fields whose winning source/value changed
 * @param conflictCountA           total field conflicts under rules A
 * @param conflictCountB           total field conflicts under rules B
 */
public record ResolutionComparison(
        String snapshotVersion,
        String rulesIdA,
        String rulesIdB,
        boolean identical,
        int entityCountA,
        int entityCountB,
        List<EntityClusterDifference> clusterDifferences,
        List<String> ambiguousOnlyInA,
        List<String> ambiguousOnlyInB,
        int totalFieldWinnerChanges,
        int conflictCountA,
        int conflictCountB
) {

    /** Canonical fields compared when two rule sets resolve the same cluster. */
    public static final List<String> CANONICAL_FIELDS =
            List.of("symbol", "name", "sequence", "organism");

    public static String ambiguityToken(AmbiguousLink link) {
        return link.reason() + "|" + link.token();
    }

    public static String clusterKey(ResolvedEntity entity) {
        return entity.type().name() + ":"
                + entity.identifiersBySource().entrySet().stream()
                .sorted(java.util.Map.Entry.comparingByKey())
                .map(e -> e.getKey() + "=" + e.getValue())
                .reduce((a, b) -> a + "," + b).orElse("");
    }
}
