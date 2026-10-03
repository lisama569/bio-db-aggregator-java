package com.github.lisama569.biodbaggregatorjava.ruleset;

/**
 * Per-field adjudication strategies selectable from a declared {@link RuleSet}.
 */
public enum FieldResolutionStrategy {
    /** First non-blank value in (field override, then global) source-priority order wins. */
    SOURCE_PRIORITY,
    /** Longest non-blank value wins; length ties broken by source-priority order. */
    PREFER_LONGEST
}
