package com.github.lisama569.biodbaggregatorjava.ruleset;

import java.util.List;

/**
 * Loads rule set definitions from local resources (classpath directory or local
 * filesystem directories). Implementations never reach the network and never hard-code
 * rule content; every definition is validated and malformed ones are rejected with a
 * distinguishable {@link RuleSetFailureReason}.
 */
public interface RuleSetLoader {

    /** Parse and validate one raw JSON rule-set definition with a known origin name. */
    RuleSet load(String origin, byte[] raw);

    /** Load and validate every rule set reachable from the configured local locations. */
    List<RuleSet> loadAll();
}
