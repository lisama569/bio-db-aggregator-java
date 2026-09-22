package com.github.lisama569.biodbaggregatorjava.store;

import com.github.lisama569.biodbaggregatorjava.model.BioRecord;
import com.github.lisama569.biodbaggregatorjava.model.RejectedRecord;
import com.github.lisama569.biodbaggregatorjava.model.ResolutionReport;
import com.github.lisama569.biodbaggregatorjava.model.Snapshot;
import com.github.lisama569.biodbaggregatorjava.resolution.IdentifierResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

/**
 * In-memory implementation with copy-on-publish semantics.
 *
 * <p>All mutations happen under one lock and finish by assigning one immutable
 * {@link SnapshotView} to a {@code volatile} slot. Readers that captured a view keep
 * reading exactly that view even while another thread republishes or switches versions;
 * they therefore always see a complete single-version snapshot, never a mix.</p>
 */
@Component
public class InMemorySnapshotStore implements SnapshotStore {

    private static final Logger log = LoggerFactory.getLogger(InMemorySnapshotStore.class);

    private final IdentifierResolver resolver;
    private final ReentrantLock lock = new ReentrantLock();

    /** version -> source -> stored import (the authoritative content of the version). */
    private final Map<String, Map<String, SourceImport>> imports = new ConcurrentHashMap<>();
    /** version -> immutable published view. Published by volatile-safe put after full build. */
    private final Map<String, SnapshotView> views = new ConcurrentHashMap<>();
    /** Deterministic, append-only order in which versions were first published. */
    private final java.util.List<String> versionOrder =
            java.util.Collections.synchronizedList(new java.util.ArrayList<>());
    private volatile String currentVersion;

    public InMemorySnapshotStore(IdentifierResolver resolver) {
        this.resolver = resolver;
    }

    @Override
    public PublishResult importSource(SourceImport in) {
        lock.lock();
        try {
            Map<String, SourceImport> perSource =
                    imports.computeIfAbsent(in.version(), k -> new ConcurrentHashMap<>());
            // Atomic claim: two threads importing the same (version, source) for the first
            // time must not overwrite each other's stored contribution.
            SourceImport winner = perSource.putIfAbsent(in.source(), in);
            ImportStatus status;
            if (winner != null) {
                if (winner.contentHash().equals(in.contentHash())) {
                    // Same content: idempotent replay. No new records, no count drift.
                    status = ImportStatus.DUPLICATE;
                    log.info("import idempotent replay: version={} source={} hash={}",
                            in.version(), in.source(), in.contentHash());
                } else {
                    // A released version cannot be silently overwritten with different data.
                    log.warn("import rejected: version={} source={} existingHash={} newHash={}",
                            in.version(), in.source(), winner.contentHash(), in.contentHash());
                    return new PublishResult(ImportStatus.CONFLICTING_REIMPORT, views.get(in.version()));
                }
            } else {
                status = ImportStatus.CREATED;
                log.info("import accepted: version={} source={} format={} accepted={} rejected={} conflicts={} hash={}",
                        in.version(), in.source(), in.format(), in.accepted().size(),
                        in.rejected().size(), in.conflicts().size(), in.contentHash());
            }
            SnapshotView view = republish(in.version());
            // Current pointer only moves on explicit switchVersion. The sole exception is
            // the very first publish in a fresh store, which gives readers an initial view;
            // this is why callers must fully import a version before switching others in.
            if (currentVersion == null) {
                currentVersion = in.version();
            }
            return new PublishResult(status, view);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean switchVersion(String version) {
        lock.lock();
        try {
            if (!views.containsKey(version)) {
                log.warn("switch rejected: unknown version={}", version);
                return false;
            }
            String previous = currentVersion;
            currentVersion = version;
            log.info("version switched: {} -> {}", previous, version);
            return true;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Optional<String> currentVersion() {
        return Optional.ofNullable(currentVersion);
    }

    @Override
    public Optional<SnapshotView> view(String version) {
        return Optional.ofNullable(views.get(version));
    }

    @Override
    public Optional<SnapshotView> currentView() {
        String v = currentVersion;
        return v == null ? Optional.empty() : Optional.ofNullable(views.get(v));
    }

    @Override
    public List<String> versions() {
        synchronized (versionOrder) {
            return List.copyOf(versionOrder);
        }
    }

    /** Rebuild one fully deterministic immutable view from all stored imports. */
    private SnapshotView republish(String version) {
        Map<String, SourceImport> perSource = imports.get(version);
        List<BioRecord> records = new ArrayList<>();
        List<RejectedRecord> rejected = new ArrayList<>();
        List<RejectedRecord> conflicts = new ArrayList<>();

        // sources aggregated in ascending name order => independent of import arrival order
        List<String> sources = new ArrayList<>(perSource.keySet());
        sources.sort(Comparator.naturalOrder());
        for (String source : sources) {
            SourceImport si = perSource.get(source);
            records.addAll(si.accepted());
            rejected.addAll(si.rejected());
            conflicts.addAll(si.conflicts());
        }
        records.sort(Comparator
                .comparing(BioRecord::source)
                .thenComparing(BioRecord::type)
                .thenComparing(BioRecord::localId));

        Snapshot snapshot = new Snapshot(version, List.copyOf(records),
                List.copyOf(rejected), List.copyOf(conflicts));
        ResolutionReport report = resolver.resolve(snapshot);
        SnapshotView view = new SnapshotView(
                snapshot.records(), snapshot.rejected(), snapshot.conflicts(), report);
        views.put(version, view);
        if (!versionOrder.contains(version)) {
            versionOrder.add(version);
        }
        return view;
    }
}
