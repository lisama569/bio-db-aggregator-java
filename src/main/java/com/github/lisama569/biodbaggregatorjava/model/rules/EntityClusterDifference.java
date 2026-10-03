package com.github.lisama569.biodbaggregatorjava.model.rules;

import java.util.List;

/**
 * A divergence in how one logical cluster (identified by its member source-local ids)
 * comes out under two rule sets.
 *
 * @param clusterKey          stable cluster identity: TYPE + sorted member identifiers
 * @param entityKeyA          entity key under rules A (null if the cluster split/merged away)
 * @param entityKeyB          entity key under rules B
 * @param changedFields       canonical fields whose winning value/source differs
 * @param conflictCountDelta  conflictsB - conflictsA
 * @param mergeChanged        true when cluster membership (which records merged) differs
 */
public record EntityClusterDifference(
        String clusterKey,
        String entityKeyA,
        String entityKeyB,
        List<FieldWinnerDifference> changedFields,
        int conflictCountDelta,
        boolean mergeChanged
) {
}
