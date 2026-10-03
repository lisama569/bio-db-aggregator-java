package com.github.lisama569.biodbaggregatorjava.store;

import com.github.lisama569.biodbaggregatorjava.model.BioRecord;
import com.github.lisama569.biodbaggregatorjava.model.RejectedRecord;
import com.github.lisama569.biodbaggregatorjava.model.ResolutionReport;
import com.github.lisama569.biodbaggregatorjava.model.SourceFormat;

import java.util.List;
import java.util.Optional;

/**
 * Versioned, append-only snapshot store.
 *
 * <p>A <b>version</b> is a logical data release assembled from one or more
 * <b>source imports</b>. Importing the same (version, source) twice with identical
 * content is idempotent; importing it with different content is rejected because a
 * released version is immutable.</p>
 *
 * <p>Readers always observe a complete single-version view: a read captures one
 * {@link SnapshotView} for its whole duration, so publishing/switching versions while
 * queries are in flight never mixes old and new data.</p>
 */
public interface SnapshotStore {

    /**
     * Atomically add (or replay) one source import into a version, then republish the
     * aggregated immutable view for that version.
     */
    PublishResult importSource(SourceImport input);

    /** Atomically point "current" at an already published version; false if unknown. */
    boolean switchVersion(String version);

    Optional<String> currentVersion();

    Optional<SnapshotView> view(String version);

    Optional<SnapshotView> currentView();

    List<String> versions();

    /**
     * Resolve (or fetch the cached immutable report for) one version under an explicit
     * rule set id. The same (version, rulesId) always returns an equal report.
     * Unknown rule set -> {@link com.github.lisama569.biodbaggregatorjava.ruleset.RuleSetException}.
     */
    ResolutionReport resolutionFor(String version, String rulesId);

    /** Rule set id under which the version's pinned conclusion was first published. */
    Optional<String> pinnedRules(String version);

    /** Currently selected rule set id used for unqualified reads/new imports. */
    String currentRulesId();

    /** Atomically select a registered rule set as the current one; false if unknown. */
    boolean switchCurrentRules(String rulesId);

    /** Immutable bundle handed to readers; capturing one guarantees a single-version view. */
    record SnapshotView(List<BioRecord> records,
                        List<RejectedRecord> rejected,
                        List<RejectedRecord> conflicts,
                        java.util.Map<String, ResolutionReport> resolutions,
                        String pinnedRulesId) {

        /** The version's pinned (published) conclusion. */
        public ResolutionReport resolution() {
            return resolutions.get(pinnedRulesId);
        }

        /** The conclusion computed under a specific rule set (must exist in the bundle). */
        public ResolutionReport resolution(String rulesId) {
            return resolutions.get(rulesId);
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
