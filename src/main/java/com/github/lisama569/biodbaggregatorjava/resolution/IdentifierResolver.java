package com.github.lisama569.biodbaggregatorjava.resolution;

import com.github.lisama569.biodbaggregatorjava.model.ResolutionReport;
import com.github.lisama569.biodbaggregatorjava.model.Snapshot;
import com.github.lisama569.biodbaggregatorjava.rules.RuleSet;

/**
 * Merges normalized records of one snapshot into resolved entities.
 *
 * <p>Resolution is deterministic for a fixed {@code (snapshot, ruleSet)} pair: the same
 * data under the same immutable rule set always yields identical conclusions, regardless
 * of thread scheduling or invocation order. Rule sets are passed explicitly, so changing
 * or switching the active rule set never alters a conclusion produced under another one.</p>
 */
public interface IdentifierResolver {

    String rulesVersion();

    /** Resolve under an explicitly named, immutable rule set. */
    ResolutionReport resolve(Snapshot snapshot, RuleSet ruleSet);

    /** Resolve under the resolver's built-in legacy rule set. */
    ResolutionReport resolve(Snapshot snapshot);
}
