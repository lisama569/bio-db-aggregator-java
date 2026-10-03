package com.github.lisama569.biodbaggregatorjava.model;

import java.util.List;

/**
 * How merging differed for one entity cluster between two rule sets.
 *
 * <p>Merging is driven by xref equivalence (not by priority), so most clusters are equal.
 * A cluster is identified by the sorted set of its member home tokens
 * ({@code source:localId}); this record captures clusters present only under one rule
 * set (which can occur if a rule set declares a different source universe / strictness)
 * and clusters present in both but merged differently.</p>
 */
public record EntityClusterDifference(
        String signature,
        String entityKeyUnderRulesA,
        String entityKeyUnderRulesB,
        List<String> membersUnderA,
        List<String> membersUnderB
) {
}
