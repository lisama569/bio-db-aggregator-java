package com.github.lisama569.biodbaggregatorjava.store;

import com.github.lisama569.biodbaggregatorjava.model.BioRecord;
import com.github.lisama569.biodbaggregatorjava.model.RejectedRecord;
import com.github.lisama569.biodbaggregatorjava.model.ResolutionReport;
import com.github.lisama569.biodbaggregatorjava.model.Snapshot;
import com.github.lisama569.biodbaggregatorjava.resolution.IdentifierResolver;
import com.github.lisama569.biodbaggregatorjava.rules.RuleSet;
import com.github.lisama569.biodbaggregatorjava.rules.RuleSetRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * In-memory implementation with copy-on-publish semantics.
 *
 * <p>All mutations happen under one lock and finish by assigning one immutable
 * {@link SnapshotView} to a {@link ConcurrentHashMap} slot (itself published safely).
 * Readers that captured a view keep reading exactly that view even while another thread
 * republishes, switches versions or re-resolves under another rule set; they therefore
 * always see a complete single-version/single-ruleset snapshot, never a mix.</p>
 *
 * <p>Each version permanently pins the exact immutable {@link RuleSet} instance used at
 * first publication. Alternative resolutions of the same data under other rule sets are
 * cached in the same view but never replace the pinned (historical) conclusion.</p>
 */
@Component
public class InMemorySnapshotStore implements SnapshotStore {

    private static final Logger log = LoggerFactory.getLogger(InMemorySnapshotStore.class);

    private final IdentifierResolver resolver;
    private final RuleSetRegistry registry;
    private final ReentrantLock lock = new ReentrantLock();

    /** version -> source -> stored import (the authoritative content of the version). */
    private final Map<String, Map<String, SourceImport>> imports = new ConcurrentHashMap<>();
    /** version -> immutable published view. */
    private final Map<String, SnapshotView> views = new ConcurrentHashMap<>();
    /** Deterministic, append-only order in which versions were first published. */
    private final List<String> versionOrder =
            java.util.Collections.synchronizedList(new java.util.ArrayList<>());
    private volatile String currentVersion;

    public InMemorySnapshotStore(IdentifierResolver resolver, RuleSetRegistry registry) {
        this.resolver = resolver;
        this.registry = registry;
    }

    @Override
    public void activateRuleSet(RuleSet ruleSet) {
        log.info("active rule set publication acknowledged: {}", ruleSet.id());
    }

    @Override
    public Optional<RuleSet> activeRuleSet() {
        return registry.active();
    }

    @Override
    public PublishResult importSource(SourceImport in, RuleSet requestedRules) {
        lock.lock();
        try {
            SnapshotView existingVersion = views.get(in.version());
            // The first publication pins a rule set: the explicitly requested one if the
            // caller named it, otherwise the currently active rule set. The pin is immutable
            // for the version's whole lifetime; this argument is ignored on later imports.
            RuleSet rules;
            if (existingVersion != null) {
                rules = existingVersion.pinnedRules();
            } else if (requestedRules != null) {
                rules = requestedRules;
            } else {
                // Single source of truth for the active rule set: read it from the registry
                // atomically, so a rule switch is immediately visible to new publications.
                rules = registry.requireActive();
            }
            if (rules == null) {
                throw new IllegalStateException("no active rule set; cannot publish");
            }

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
            SnapshotView view = republish(in.version(), rules);
            // Current pointer only moves on explicit switchVersion. The sole exception is
            // the very first publish in a fresh store, which gives readers an initial view.
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
            log.info("version switched: {} -> {} (pinned rules={})",
                    previous, version, views.get(version).pinnedRules().id());
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

    @Override
    public ResolutionReport resolutionUnder(String version, RuleSet ruleSet) {
        // Fast lock-free path: the immutable cache map is published with its view.
        SnapshotView existing = views.get(version);
        if (existing != null) {
            ResolutionReport cached = existing.resolutionsByRules().get(ruleSet.id());
            if (cached != null) {
                return cached;
            }
        }
        lock.lock();
        try {
            SnapshotView view = views.get(version);
            if (view == null) {
                throw new IllegalArgumentException("unknown snapshot version: " + version);
            }
            ResolutionReport cached = view.resolutionsByRules().get(ruleSet.id());
            if (cached != null) {
                return cached;
            }
            Snapshot snapshot = new Snapshot(version, view.records(), view.rejected(),
                    view.conflicts());
            ResolutionReport report = resolver.resolve(snapshot, ruleSet);
            Map<String, ResolutionReport> merged = new LinkedHashMap<>(view.resolutionsByRules());
            merged.put(ruleSet.id(), report);
            // Re-publish one complete immutable view: readers in flight keep the old one
            // and therefore never observe a half-rebuilt rules result.
            SnapshotView updated = new SnapshotView(view.records(), view.rejected(),
                    view.conflicts(), view.pinnedResolution(), view.pinnedRules(),
                    Map.copyOf(merged));
            views.put(version, updated);
            log.info("alternative resolution cached: version={} rules={} entities={}",
                    version, ruleSet.id(), report.entities().size());
            return report;
        } finally {
            lock.unlock();
        }
    }

    /** Rebuild one fully deterministic immutable view from all stored imports. */
    private SnapshotView republish(String version, RuleSet active) {
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

        SnapshotView previous = views.get(version);
        // A version pins the exact rule set of its FIRST publication; later imports keep
        // re-resolving under it even if the active rule set has since changed.
        RuleSet pinned = previous == null ? active : previous.pinnedRules();
        ResolutionReport pinnedReport = resolver.resolve(snapshot, pinned);

        // Previously computed alternative conclusions are rebuilt from their rule-set
        // instances (looked up in the registry by id) so newly imported sources are
        // included. If a rule set has since been removed by a reload, the last computed
        // immutable conclusion is retained for historical retrieval.
        Map<String, ResolutionReport> byRules = new LinkedHashMap<>();
        byRules.put(pinned.id(), pinnedReport);
        if (previous != null) {
            for (Map.Entry<String, ResolutionReport> e
                    : previous.resolutionsByRules().entrySet()) {
                if (e.getKey().equals(pinned.id())) {
                    continue;
                }
                RuleSet otherRules = registry.find(e.getKey()).orElse(null);
                if (otherRules != null) {
                    byRules.put(e.getKey(), resolver.resolve(snapshot, otherRules));
                } else {
                    byRules.put(e.getKey(), e.getValue());
                }
            }
        }

        SnapshotView view = new SnapshotView(
                snapshot.records(), snapshot.rejected(), snapshot.conflicts(),
                pinnedReport, pinned, Map.copyOf(byRules));
        views.put(version, view);
        if (!versionOrder.contains(version)) {
            versionOrder.add(version);
        }
        return view;
    }

}