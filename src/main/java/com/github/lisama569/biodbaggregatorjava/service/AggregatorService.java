package com.github.lisama569.biodbaggregatorjava.service;

import com.github.lisama569.biodbaggregatorjava.model.ImportReport;
import com.github.lisama569.biodbaggregatorjava.model.ResolutionComparison;
import com.github.lisama569.biodbaggregatorjava.model.ResolutionReport;
import com.github.lisama569.biodbaggregatorjava.model.ResolvedEntity;
import com.github.lisama569.biodbaggregatorjava.model.SourceFormat;
import com.github.lisama569.biodbaggregatorjava.model.Snapshot;
import com.github.lisama569.biodbaggregatorjava.rules.RuleSet;
import com.github.lisama569.biodbaggregatorjava.rules.RuleSetReloadResult;

import java.util.List;
import java.util.Optional;

/**
 * Facade: import snapshots, switch versions, manage rule sets and query consistent,
 * rule-reproducible resolved views.
 */
public interface AggregatorService {

    /**
     * Import a local snapshot export under the currently active rule set.
     * Idempotent for the same (version, source, content).
     */
    ImportReport importSnapshot(String version, String source, SourceFormat format, String rawInput);

    /**
     * Import while explicitly naming the rule set. On the version's first publication that
     * rule set is pinned; if the version is already pinned to another rule set the attempt
     * is refused with {@code RULE_SET_MISMATCH} (no silent re-adjudication).
     */
    ImportReport importSnapshot(String version, String source, SourceFormat format,
                                String rawInput, String ruleSetId);

    boolean switchVersion(String version);

    Optional<String> currentVersion();

    List<String> versions();

    Optional<Snapshot> getSnapshot(String version);

    /** The stamped (pinned-rule-set) conclusion of a version. */
    Optional<ResolutionReport> getResolution(String version);

    /** Reproduce/recompute a version's conclusion under an explicit rule set. */
    ResolutionReport getResolution(String version, String ruleSetId);

    /** Compare one data version's conclusions under two rule sets. */
    ResolutionComparison compareResolutions(String version, String ruleSetA, String ruleSetB);

    Optional<ResolvedEntity> resolveIdentifier(String identifier);

    /** Point lookup against current version under an explicit rule set. */
    Optional<ResolvedEntity> resolveIdentifier(String identifier, String ruleSetId);

    List<ResolvedEntity> queryAll();

    /** All entities of the current version under an explicit rule set. */
    List<ResolvedEntity> queryAll(String ruleSetId);

    // ---- rule-set management -------------------------------------------------

    List<RuleSet> listRuleSets();

    Optional<RuleSet> activeRuleSet();

    /** Switch the active rule set; false if the id is not loaded. */
    boolean activateRuleSet(String ruleSetId);

    /** Re-scan local rule-set files and atomically republish the catalog. */
    RuleSetReloadResult reloadRuleSets();
}
