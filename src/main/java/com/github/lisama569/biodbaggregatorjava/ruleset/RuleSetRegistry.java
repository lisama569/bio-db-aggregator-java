package com.github.lisama569.biodbaggregatorjava.ruleset;

import java.util.List;
import java.util.Optional;

/**
 * Read-mostly catalog of validated, immutable rule sets.
 *
 * <p>Rule sets are content-addressed by id. Re-registering the same id with the same
 * content is idempotent; the same id with different content is rejected
 * ({@link RuleSetFailureReason#DUPLICATE_RULESET_ID}). Nothing here ever silently replaces
 * a rule set, so conclusions stamped with an id remain reproducible forever.</p>
 */
public interface RuleSetRegistry {

    /** Register a validated rule set; idempotent for identical content. */
    void register(RuleSet ruleSet);

    Optional<RuleSet> get(String ruleSetId);

    /** Deterministic ascending-id view of all registered rule sets. */
    List<RuleSet> all();

    /** The rule set used when a query/import does not name one explicitly. */
    RuleSet current();

    String currentId();

    /** Atomically make a registered rule set the one used by default. */
    boolean switchCurrent(String ruleSetId);
}
