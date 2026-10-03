package com.github.lisama569.biodbaggregatorjava.model;

import java.util.List;

/**
 * Differences between two conclusions produced from the SAME data snapshot under two
 * different rule sets. Covers the four user-visible dimensions: entity merging, field
 * winners, recorded conflicts and ambiguities.
 */
public record ResolutionComparison(
        String snapshotVersion,
        String rulesSetA,
        String rulesSetB,
        List<EntityClusterDifference> clusterDifferences,
        List<FieldDecisionDifference> fieldDifferences,
        List<String> conflictsOnlyUnderA,
        List<String> conflictsOnlyUnderB,
        List<String> ambiguitiesOnlyUnderA,
        List<String> ambiguitiesOnlyUnderB,
        boolean equivalent
) {
}
