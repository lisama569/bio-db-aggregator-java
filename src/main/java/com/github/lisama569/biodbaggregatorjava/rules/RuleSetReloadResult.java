package com.github.lisama569.biodbaggregatorjava.rules;

import java.util.List;

/** Result of an atomic rule-set reload: what is active now and what was refused. */
public record RuleSetReloadResult(
        String activeRuleSetId,
        List<String> loadedIds,
        List<RuleSetLoadFailure> failures
) {
    public RuleSetReloadResult {
        loadedIds = loadedIds == null ? List.of() : List.copyOf(loadedIds);
        failures = failures == null ? List.of() : List.copyOf(failures);
    }
}
