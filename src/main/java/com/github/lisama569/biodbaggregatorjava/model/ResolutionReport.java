package com.github.lisama569.biodbaggregatorjava.model;

import java.util.List;

/** Result of resolving a whole snapshot version. */
public record ResolutionReport(
        String snapshotVersion,
        String rulesVersion,
        List<ResolvedEntity> entities,
        int conflictCount,
        List<AmbiguousLink> ambiguities
) {
}
