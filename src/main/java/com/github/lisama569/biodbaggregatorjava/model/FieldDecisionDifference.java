package com.github.lisama569.biodbaggregatorjava.model;

/**
 * One field whose final winner changed between two rule sets for the same merged entity.
 *
 * @param signature  sorted member-token signature identifying the entity cluster
 * @param field      symbol/name/sequence/organism
 * @param winnerSourceA winner source under rule set A (null if absent)
 * @param winnerValueA  winning value under rule set A
 * @param winnerSourceB winner source under rule set B (null if absent)
 * @param winnerValueB  winning value under rule set B
 */
public record FieldDecisionDifference(
        String signature,
        String field,
        String winnerSourceA,
        String winnerValueA,
        String winnerSourceB,
        String winnerValueB
) {
}
