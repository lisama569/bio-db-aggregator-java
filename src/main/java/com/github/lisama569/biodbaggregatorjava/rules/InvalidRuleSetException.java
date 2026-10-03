package com.github.lisama569.biodbaggregatorjava.rules;

/**
 * Thrown when a rule set declaration is structurally invalid. Carries a
 * {@link RuleRejectionReason} so callers (tests, REST layer) can distinguish
 * failure kinds instead of falling back to a default rule set.
 */
public class InvalidRuleSetException extends RuntimeException {

    private final RuleRejectionReason reason;
    private final String ruleSetId;

    public InvalidRuleSetException(RuleRejectionReason reason, String ruleSetId, String detail) {
        super("rule set '" + ruleSetId + "' rejected: " + reason + " - " + detail);
        this.reason = reason;
        this.ruleSetId = ruleSetId;
    }

    public RuleRejectionReason reason() {
        return reason;
    }

    public String ruleSetId() {
        return ruleSetId;
    }
}
