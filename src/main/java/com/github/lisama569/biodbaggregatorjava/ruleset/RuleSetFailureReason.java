package com.github.lisama569.biodbaggregatorjava.ruleset;

/**
 * Distinguishable reasons a rule set is rejected or cannot be used. A bad rule set is
 * always rejected explicitly; the service never silently falls back to default rules.
 */
public enum RuleSetFailureReason {
    /** Definition is not syntactically valid JSON or has the wrong top-level shape. */
    INVALID_SYNTAX,
    /** Rule set id is missing or blank. */
    BLANK_ID,
    /** sourcePriority is missing or empty. */
    EMPTY_PRIORITY,
    /** The same source appears more than once in a priority list. */
    DUPLICATE_PRIORITY_SOURCE,
    /** A field declares an adjudication strategy that does not exist. */
    UNKNOWN_STRATEGY,
    /** A field override references a field that is not part of the canonical model. */
    UNKNOWN_FIELD,
    /** A field override names a source not declared in the global sourcePriority. */
    OVERRIDE_SOURCE_NOT_DECLARED,
    /** A rule set with the same id but different content is already registered. */
    DUPLICATE_RULESET_ID,
    /** The configured default rule set id was not found among loaded definitions. */
    DEFAULT_RULESET_NOT_FOUND,
    /** A query/switch/pin references a rule set id that is not registered. */
    UNKNOWN_RULESET
}
