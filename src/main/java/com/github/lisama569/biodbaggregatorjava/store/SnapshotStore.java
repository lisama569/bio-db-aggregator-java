package com.github.lisama569.biodbaggregatorjava.store;

import com.github.lisama569.biodbaggregatorjava.model.BioRecord;
import com.github.lisama569.biodbaggregatorjava.model.RejectedRecord;
import com.github.lisama569.biodbaggregatorjava.model.ResolutionReport;
import com.github.lisama569.biodbaggregatorjava.model.SourceFormat;
import com.github.lisama569.biodbaggregatorjava.rules.RuleSet;

import java.util.List;
import java.util.Optional;

/**
 * Versioned, append-only snapshot store with per-version pinned rule sets.
 *
 * <p>A <b>version</b> is a logical data release assembled from one or more
 * <b>source imports</b>. The first import of a version <b>pins</b> the rule set used to
 * adjudicate it; that immutable rule-set instance stays attached forever, so changing or
 * switching the active rule set never rewrites the version's stamped conclusion.</p>
 *
 * <p>Readers always observe a complete single-version, single-ruleset view: a read
 * captures one {@link SnapshotView} for its whole duration, so importing, switching
 * versions or switching the active rule set while queries are in flight never mixes old
 * and new data or rules.</p>
 */
public interface SnapshotStore {

    /**
     * Atomically add (or replay) one source import into a version, then republish the
     * aggregated immutable view. The first import of a version pins {@code activeRules}
     * as its adjudication rule set; later imports keep the pinned rule set regardless of
     * the passed one.
     */
    PublishResult importSource(SourceImport input, RuleSet activeRules);

    /** Atomically point "current" at an already published version; false if unknown. */
    boolean switchVersion(String version);

    /** Atomically change which rule set newly published versions and current queries use. */
    void activateRuleSet(RuleSet ruleSet);

    Optional<String> currentVersion();

    Optional<RuleSet> activeRuleSet();

    Optional<SnapshotView> view(String version);

    Optional<SnapshotView> currentView();

    List<String> versions();

    /**
     * Resolve a version under an explicit, immutable rule set and cache the conclusion
     * inside the version's view, never disturbing its pinned conclusion.
     */
    ResolutionReport resolutionUnder(String version, RuleSet ruleSet);

    /**
     * Produce several conclusions of the SAME version in one atomic step: both reports are
     * resolved from a single captured {@link SnapshotView} (the same immutable record set),
     * so a concurrent import/republish, version switch or rule-set switch can never make a
     * comparison pair straddle old and new data. The version's pinned conclusion itself is
     * never modified.
     *
     * @return the conclusions in the same order as {@code ruleSets}
     */
    java.util.List<ResolutionReport> resolutionsUnder(String version,
                                                       java.util.List<RuleSet> ruleSets);

    /** Immutable bundle handed to readers; capturing one guarantees a single-version view. */
    record SnapshotView(List<BioRecord> records,
                        List<RejectedRecord> rejected,
                        List<RejectedRecord> conflicts,
                        ResolutionReport pinnedResolution,
                        RuleSet pinnedRules,
                        java.util.Map<String, ResolutionReport> resolutionsByRules) {

        /** The resolution to use when no rule set is explicitly named (the stamped one). */
        public ResolutionReport resolution() {
            return pinnedResolution;
        }
    }

    /** One source's parsed contribution to a version. */
    record SourceImport(String version, String source, SourceFormat format, String contentHash,
                        List<BioRecord> accepted, List<RejectedRecord> rejected,
                        List<RejectedRecord> conflicts) {
    }

    /** Outcome of an import attempt. */
    enum ImportStatus { CREATED, DUPLICATE, CONFLICTING_REIMPORT }

    record PublishResult(ImportStatus status, SnapshotView view) {
    }
}
