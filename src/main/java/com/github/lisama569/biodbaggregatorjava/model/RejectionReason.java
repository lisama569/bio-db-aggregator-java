package com.github.lisama569.biodbaggregatorjava.model;

/** Distinguishable reasons a raw record is rejected or flagged as a conflict. */
public enum RejectionReason {
    MALFORMED_SYNTAX,
    MISSING_REQUIRED_FIELD,
    UNKNOWN_ENTITY_TYPE,
    INVALID_IDENTIFIER,
    DUPLICATE_SOURCE_IDENTIFIER
}
