package com.github.lisama569.biodbaggregatorjava.rules;

/**
 * Distinguishable reasons a declared rule set is refused at load/reload time.
 * A rejected rule set is never silently replaced by defaults.
 */
public enum RuleRejectionReason {
    /** Rule set id missing or blank. */
    MISSING_RULE_SET_ID,
    /** Source priority list missing or empty. */
    MISSING_SOURCE_PRIORITY,
    /** The same source appears more than once in the priority list. */
    DUPLICATE_SOURCE_IN_PRIORITY,
    /** A priority source (or field-rule source) is absent from the declared sources. */
    UNKNOWN_SOURCE_REFERENCED,
    /** A per-field override references a field the resolver does not adjudicate. */
    UNKNOWN_FIELD_REFERENCED,
    /** Two rule set files declare the same rule set id. */
    DUPLICATE_RULE_SET_ID,
    /** The rule set file cannot be read/parsed (bad JSON, IO error). */
    MALFORMED_RULE_DOCUMENT
}
