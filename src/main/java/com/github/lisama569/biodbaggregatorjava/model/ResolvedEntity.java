package com.github.lisama569.biodbaggregatorjava.model;

import java.util.List;
import java.util.Map;

/**
 * A single logical entity merged from one or more source records.
 *
 * <p>{@code fieldWinners} records, for every adjudicated field
 * (symbol/name/sequence/organism) that has a non-null winning value, the source that
 * supplied it under this entity's rule set. It is populated even when no
 * {@link ResolutionConflict} exists (all sources agree on the value), so comparisons
 * across rule sets can detect winner-source changes whose winning value stayed equal.</p>
 */
public record ResolvedEntity(
        String entityKey,
        EntityType type,
        String canonicalSymbol,
        String canonicalName,
        String sequence,
        String organism,
        Map<String, String> identifiersBySource,
        List<ResolutionConflict> fieldConflicts,
        List<String> contributingSources,
        Map<String, String> fieldWinners,
        String snapshotVersion,
        String rulesVersion
) {
    /** Source that won the given field, or null when the field has no value. */
    public String fieldWinner(String field) {
        return fieldWinners == null ? null : fieldWinners.get(field);
    }
}
