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

    /** Immutable bundle handed to readers; capturing one guarantees a single-version view. */
    record SnapshotView(List<BioRecord> records,
                        List<RejectedRecord> rejected,
                        List<RejectedRecord> conflicts,
                        ResolutionReport resolution) {
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
