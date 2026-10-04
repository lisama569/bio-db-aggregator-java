package com.github.lisama569.biodbaggregatorjava.model;

/**
 * One field whose final adjudication changed between two rule sets for the same merged
 * entity cluster.
 *
 * <p>A difference is reported when <b>either</b> the winning value changed <b>or</b> the
 * winning source changed - even when the value itself is byte-identical ("same value,
 * different credit"). Fields with identical value AND identical winning source are never
 * reported. The {@link #kind} attribute distinguishes the two dimensions.</p>
 *
 * @param signature  sorted member-token signature identifying the entity cluster
 * @param field      symbol/name/sequence/organism
 * @param kind       which dimension(s) changed: value, winner source, or both
 * @param winnerSourceA winner source under rule set A (null when the field has no
 *                      non-blank contributor on side A, e.g. single-side-absent)
 * @param winnerValueA  winning value under rule set A (null if absent)
 * @param winnerSourceB winner source under rule set B (null when absent on side B)
 * @param winnerValueB  winning value under rule set B (null if absent)
 */
public record FieldDecisionDifference(
        String signature,
        String field,
        FieldDifferenceKind kind,
        String winnerSourceA,
        String winnerValueA,
        String winnerSourceB,
        String winnerValueB
) {
}
