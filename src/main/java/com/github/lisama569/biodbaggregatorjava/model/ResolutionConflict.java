package com.github.lisama569.biodbaggregatorjava.model;

import java.util.List;

/**
 * A deterministic disagreement between sources for the same resolved entity.
 * The winner is chosen by source priority; losers are retained for reproducibility.
 */
public record ResolutionConflict(
        String field,
        String winnerSource,
        String winnerValue,
        List<String> loserSources,
        List<String> loserValues
) {
}
