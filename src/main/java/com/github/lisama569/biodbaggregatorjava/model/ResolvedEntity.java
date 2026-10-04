package com.github.lisama569.biodbaggregatorjava.model;

import java.util.List;
import java.util.Map;

/**
 * A single logical entity merged from one or more source records.
 *
 * @param fieldWinnerSources adjudicated field (symbol/name/sequence/organism) -> the
 *                           source whose non-blank value won, recorded for EVERY field
 *                           that has a winner - including single-source fields and fields
 *                           where all contributors agreed (no {@link ResolutionConflict}
 *                           is emitted in that case). Fields with no non-blank contributor
 *                           are simply absent from the map.
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
        Map<String, String> fieldWinnerSources,
        String snapshotVersion,
        String rulesVersion
) {
}
