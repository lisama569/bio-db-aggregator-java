package com.github.lisama569.biodbaggregatorjava.ruleset;

/**
 * Thrown when a rule set definition is invalid or a referenced rule set cannot be used.
 * The machine-readable {@link RuleSetFailureReason reason} lets callers distinguish
 * failure kinds instead of guessing from the message.
 */
public class RuleSetException extends RuntimeException {

    private final RuleSetFailureReason reason;
    private final String ruleSetId;

    public RuleSetException(RuleSetFailureReason reason, String ruleSetId, String message) {
        super(message);
        this.reason = reason;
        this.ruleSetId = ruleSetId;
    }

    public RuleSetFailureReason reason() {
        return reason;
    }

    public String ruleSetId() {
        return ruleSetId;
    }
}
