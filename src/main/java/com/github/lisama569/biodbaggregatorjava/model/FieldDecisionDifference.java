package com.github.lisama569.biodbaggregatorjava.model;

/**
 * One field whose final decision differs between two rule sets for the same merged
 * entity. A difference is reported when the winning <b>value</b> changed, when the
 * winning <b>source</b> changed (even if the value stayed identical), or both.
 *
 * @param signature           sorted member-token signature identifying the entity cluster
 * @param field               symbol/name/sequence/organism
 * @param winnerSourceA       winner source under rule set A (null if the field has no value)
 * @param winnerValueA        winning value under rule set A (null if absent)
 * @param winnerSourceB       winner source under rule set B (null if the field has no value)
 * @param winnerValueB        winning value under rule set B (null if absent)
 * @param valueChanged        true when the winning values differ (including null vs value)
 * @param winnerSourceChanged true when the winning sources differ (including null vs source)
 */
public record FieldDecisionDifference(
        String signature,
        String field,
        String winnerSourceA,
        String winnerValueA,
        String winnerSourceB,
        String winnerValueB,
        boolean valueChanged,
        boolean winnerSourceChanged
) {
}
