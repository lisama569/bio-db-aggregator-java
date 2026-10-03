package com.github.lisama569.biodbaggregatorjava.model;

import java.util.List;
import java.util.Map;

/** A single logical entity merged from one or more source records. */
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
        String snapshotVersion,
        String rulesVersion,
        Map<String, String> fieldWinnerSources
) {
}
