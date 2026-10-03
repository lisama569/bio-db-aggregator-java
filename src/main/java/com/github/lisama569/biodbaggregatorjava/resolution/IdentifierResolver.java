package com.github.lisama569.biodbaggregatorjava.resolution;

import com.github.lisama569.biodbaggregatorjava.model.ResolutionReport;
import com.github.lisama569.biodbaggregatorjava.model.Snapshot;
import com.github.lisama569.biodbaggregatorjava.ruleset.RuleSet;

/**
 * Merges normalized records of one snapshot into resolved entities.
 * Implementations are deterministic and versioned (rulesVersion), so historical
 * conclusions remain reproducible when rules change.
 */
public interface IdentifierResolver {

    String rulesVersion();

    ResolutionReport resolve(Snapshot snapshot);

    /**
     * Resolve one snapshot under an explicitly named, immutable {@link RuleSet}. The same
     * snapshot under the same rule set always yields an equal report, regardless of the
     * rule set currently selected or the order/concurrency of execution.
     */
    ResolutionReport resolve(Snapshot snapshot, RuleSet ruleSet);
}
