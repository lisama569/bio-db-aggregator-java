package com.github.lisama569.biodbaggregatorjava.store;

import com.github.lisama569.biodbaggregatorjava.model.BioRecord;
import com.github.lisama569.biodbaggregatorjava.model.RejectedRecord;
import com.github.lisama569.biodbaggregatorjava.model.ResolutionReport;
import com.github.lisama569.biodbaggregatorjava.model.Snapshot;
import com.github.lisama569.biodbaggregatorjava.resolution.IdentifierResolver;
import com.github.lisama569.biodbaggregatorjava.ruleset.DefaultRuleSetRegistry;
import com.github.lisama569.biodbaggregatorjava.ruleset.RuleSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * In-memory implementation with copy-on-publish semantics and per-rule-set conclusions.
 *
 * <p>All mutations happen under one lock and finish by assigning one immutable
 * {@link SnapshotView} to a per-version slot. Readers that captured a view keep reading
 * exactly that view even while another thread republishes, switches versions or switches
 * rule sets; they therefore always see one complete (version, rules) conclusion, never a
 * mix.</p>
 *
 * <h2>Pinning and coexistence</h2>
 * <p>Every version, when first created, is <b>pinned</b> to the rule set id selected at
 * that moment. The pinned conclusion never changes afterwards; resolving the same version
 * under another rule set produces an additional immutable report cached alongside it, so
 * one data snapshot can carry several rule-specific conclusions simultaneously.</p>
 */
@Component
public class InMemorySnapshotStore implements SnapshotStore {

    private static final Logger log = LoggerFactory.getLogger(InMemorySnapshotStore.class);

    private final IdentifierResolver resolver;
    private final DefaultRuleSetRegistry ruleSets;
    private final ReentrantLock lock = new ReentrantLock();

    /** version -> source -> stored import (the authoritative content of the version). */
    private final Map<String, Map<String, SourceImport>> imports = new ConcurrentHashMap<>();
    /** version -> rulesId -> immutable rule-specific conclusion. */
    private final Map<String, Map<String, ResolutionReport>> reports = new ConcurrentHashMap<>();
    /** version -> rule set id the version was first published with (immutable pin). */
    private final Map<String, String> pinnedRules = new ConcurrentHashMap<>();
    /** version -> latest immutable published view (supersedes older views atomically). */
    private final Map<String, SnapshotView> views = new ConcurrentHashMap<>();
    /** Deterministic, append-only order in which versions were first published. */
    private final List<String> versionOrder =
            java.util.Collections.synchronizedList(new ArrayList<>());
    private volatile String currentVersion;

    public InMemorySnapshotStore(IdentifierResolver resolver, DefaultRuleSetRegistry ruleSets) {
        this.resolver = resolver;
        this.ruleSets = ruleSets;
    }

    @Override
    public PublishResult importSource(SourceImport in) {
        lock.lock();
        try {
            boolean newVersion = !imports.containsKey(in.version());
            Map<String, SourceImport> perSource =
                    imports.computeIfAbsent(in.version(), k -> new ConcurrentHashMap<>());
            // Atomic claim: two threads importing the same (version, source) for the first
            // time must not overwrite each other's stored contribution.
            SourceImport winner = perSource.putIfAbsent(in.source(), in);
            boolean dataChanged;
            ImportStatus status;
            if (winner != null) {
                if (winner.contentHash().equals(in.contentHash())) {
                    // Same content: idempotent replay. No new records, no count drift.
                    status = ImportStatus.DUPLICATE;
                    dataChanged = false;
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
                dataChanged = true;
                log.info("import accepted: version={} source={} format={} accepted={} rejected={} conflicts={} hash={}",
                        in.version(), in.source(), in.format(), in.accepted().size(),
                        in.rejected().size(), in.conflicts().size(), in.contentHash());
            }

            // A brand-new version is pinned once, to the rule set selected right now.
            // Existing versions keep their historical pin no matter how rules change later.
            if (newVersion) {
                String pin = ruleSets.currentId();
                pinnedRules.put(in.version(), pin);
                log.info("version pinned on creation: version={} pinnedRules={}", in.version(), pin);
            } else if (dataChanged) {
                // A new source contributed: rule-specific conclusions computed against the
                // old data must not survive silently; the pinned one is recomputed below.
                Map<String, ResolutionReport> stale = reports.get(in.version());
                if (stale != null) {
                    stale.clear();
                }
            }
            SnapshotView view = republish(in.version());
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
            log.info("version switched: {} -> {} (pinnedRules={})",
                    previous, version, pinnedRules.get(version));
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
    public ResolutionReport resolutionFor(String version, String rulesId) {
        lock.lock();
        try {
            if (!views.containsKey(version)) {
                throw new IllegalArgumentException("unknown snapshot version: " + version);
            }
            Map<String, ResolutionReport> perRules =
                    reports.computeIfAbsent(version, k -> new ConcurrentHashMap<>());
            ResolutionReport existing = perRules.get(rulesId);
            if (existing != null) {
                return existing;
            }
            // Unknown rule sets are rejected explicitly; never silently resolve with defaults.
            RuleSet ruleSet = ruleSets.require(rulesId);
            ResolutionReport fresh = resolver.resolve(buildSnapshot(version), ruleSet);
            perRules.put(rulesId, fresh);
            republish(version);
            log.info("additional rule resolution cached: version={} rules={} entities={} (pinnedRules={})",
                    version, rulesId, fresh.entities().size(), pinnedRules.get(version));
            return fresh;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Optional<String> pinnedRules(String version) {
        return Optional.ofNullable(pinnedRules.get(version));
    }

    @Override
    public String currentRulesId() {
        return ruleSets.currentId();
    }

    @Override
    public boolean switchCurrentRules(String rulesId) {
        // Atomic publication in the registry; existing pins/caches are intentionally untouched.
        return ruleSets.switchCurrent(rulesId);
    }

    /** Rebuild one fully deterministic immutable view from stored imports + cached reports. */
    private SnapshotView republish(String version) {
        Snapshot snapshot = buildSnapshot(version);
        String pin = pinnedRules.get(version);
        Map<String, ResolutionReport> perRules =
                reports.computeIfAbsent(version, k -> new ConcurrentHashMap<>());
        // The pinned conclusion is always recomputed against the current aggregated data
        // with the immutable pinned rule set; data edits can never reuse a stale report.
        perRules.put(pin, resolver.resolve(snapshot, ruleSets.require(pin)));

        Map<String, ResolutionReport> bundle = new HashMap<>();
        perRules.forEach((rulesId, report) -> bundle.put(rulesId, report));
        SnapshotView view = new SnapshotView(
                snapshot.records(), snapshot.rejected(), snapshot.conflicts(),
                Map.copyOf(bundle), pin);
        views.put(version, view);
        synchronized (versionOrder) {
            if (!versionOrder.contains(version)) {
                versionOrder.add(version);
            }
        }
        return view;
    }

    /** Assemble the deterministic data snapshot for a version from its source imports. */
    private Snapshot buildSnapshot(String version) {
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
        return new Snapshot(version, List.copyOf(records),
                List.copyOf(rejected), List.copyOf(conflicts));
    }
}
