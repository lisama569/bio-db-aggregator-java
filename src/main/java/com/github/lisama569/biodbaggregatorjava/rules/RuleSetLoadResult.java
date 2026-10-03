package com.github.lisama569.biodbaggregatorjava.rules;

import java.util.List;

/** Outcome of scanning rule-set locations: the valid sets plus every refusal. */
public record RuleSetLoadResult(
        List<RuleSet> loaded,
        List<RuleSetLoadFailure> failures
) {
    public RuleSetLoadResult {
        loaded = loaded == null ? List.of() : List.copyOf(loaded);
        failures = failures == null ? List.of() : List.copyOf(failures);
    }
}
