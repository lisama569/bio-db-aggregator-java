package com.github.lisama569.biodbaggregatorjava.rules;

/** Thrown when an operation explicitly names a rule set that is not loaded. */
public class UnknownRuleSetException extends IllegalArgumentException {

    private final String ruleSetId;

    public UnknownRuleSetException(String ruleSetId) {
        super("unknown rule set: " + ruleSetId);
        this.ruleSetId = ruleSetId;
    }

    public String ruleSetId() {
        return ruleSetId;
    }
}
