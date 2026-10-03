package com.github.lisama569.biodbaggregatorjava.model.rules;

/**
 * The same canonical field of the same logical entity resolves to a different winner under
 * the two compared rule sets.
 */
public record FieldWinnerDifference(
        String entityCluster,
        String field,
        String rulesAWinnerSource,
        String rulesAWinnerValue,
        String rulesBWinnerSource,
        String rulesBWinnerValue
) {
}
