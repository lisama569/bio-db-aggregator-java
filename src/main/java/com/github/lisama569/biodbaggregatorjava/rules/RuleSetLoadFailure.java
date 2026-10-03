package com.github.lisama569.biodbaggregatorjava.rules;

/** One refused rule-set declaration with a distinguishable reason and its origin file. */
public record RuleSetLoadFailure(
        String origin,
        String ruleSetId,
        RuleRejectionReason reason,
        String detail
) {
}
