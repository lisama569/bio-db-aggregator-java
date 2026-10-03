package com.github.lisama569.biodbaggregatorjava.service;

import com.github.lisama569.biodbaggregatorjava.model.BioRecord;
import com.github.lisama569.biodbaggregatorjava.model.ImportReport;
import com.github.lisama569.biodbaggregatorjava.model.RejectedRecord;
import com.github.lisama569.biodbaggregatorjava.model.ResolutionComparison;
import com.github.lisama569.biodbaggregatorjava.model.ResolutionReport;
import com.github.lisama569.biodbaggregatorjava.model.ResolvedEntity;
import com.github.lisama569.biodbaggregatorjava.model.SourceFormat;
import com.github.lisama569.biodbaggregatorjava.model.Snapshot;
import com.github.lisama569.biodbaggregatorjava.parser.ParserRegistry;
import com.github.lisama569.biodbaggregatorjava.parser.SnapshotParser;
import com.github.lisama569.biodbaggregatorjava.resolution.ResolutionComparator;
import com.github.lisama569.biodbaggregatorjava.store.SnapshotStore;
import com.github.lisama569.biodbaggregatorjava.rules.RuleSet;
import com.github.lisama569.biodbaggregatorjava.rules.RuleSetReloadResult;
import com.github.lisama569.biodbaggregatorjava.rules.RuleSetRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/** Default facade coordinating parsing, rule-set pinning and consistent reads. */
@Service
public class DefaultAggregatorService implements AggregatorService {

    /** A version already stamped with another rule set cannot be re-pinned silently. */
    public static final String RULE_SET_MISMATCH = "RULE_SET_MISMATCH";

    private static final Logger log = LoggerFactory.getLogger(DefaultAggregatorService.class);

    private final ParserRegistry parserRegistry;
    private final SnapshotStore store;
    private final RuleSetRegistry ruleSetRegistry;
    private final ResolutionComparator comparison;

    public DefaultAggregatorService(ParserRegistry parserRegistry,
                                    SnapshotStore store,
                                    RuleSetRegistry ruleSetRegistry,
                                    ResolutionComparator comparison) {
        this.parserRegistry = parserRegistry;
        this.store = store;
        this.ruleSetRegistry = ruleSetRegistry;
        this.comparison = comparison;
    }

    @Override
    public ImportReport importSnapshot(String version, String source, SourceFormat format,
                                       String rawInput) {
        return importSnapshot(version, source, format, rawInput, null);
    }

    @Override
    public ImportReport importSnapshot(String version, String source, SourceFormat format,
                                       String rawInput, String ruleSetId) {
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException("snapshot version must not be blank");
        }
        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException("source must not be blank");
        }
        if (format == null) {
            throw new IllegalArgumentException("source format must not be null");
        }
        RuleSet requested = ruleSetRegistry.resolve(ruleSetId);
        RuleSet activeOrRequested = requested;

        // Pinning guard: an existing version already has an immutable stamp. Naming another
        // rule set is refused explicitly instead of silently changing the pinned resolution.
        Optional<SnapshotStore.SnapshotView> existing = store.view(version);
        if (existing.isPresent()) {
            String pinnedId = existing.get().pinnedRules().id();
            if (ruleSetId != null && !ruleSetId.isBlank() && !ruleSetId.equals(pinnedId)) {
                log.warn("import refused: version={} pinned to rules={}, requested rules={}",
                        version, pinnedId, ruleSetId);
                return new ImportReport(version, source, RULE_SET_MISMATCH, 0, 0, List.of(),
                        0, List.of(), false, pinnedId);
            }
        }

        String content = rawInput == null ? "" : rawInput;
        log.info("import request: version={} source={} format={} bytes={} rules={}",
                version, source, format, content.length(), activeOrRequested.id());

        SnapshotParser parser = parserRegistry.parserFor(format);
        SnapshotParser.ParseResult parsed = parser.parse(source, content);
        String hash = sha256(version + "|" + source + "|" + format + "|" + content);

        SnapshotStore.SourceImport input = new SnapshotStore.SourceImport(
                version, source, format, hash,
                List.copyOf(parsed.accepted()),
                List.copyOf(parsed.rejected()),
                List.copyOf(parsed.conflicts()));
        SnapshotStore.PublishResult result = store.importSource(input, activeOrRequested);
        String pinnedId = result.view() == null ? activeOrRequested.id()
                : result.view().pinnedRules().id();

        ImportReport report = new ImportReport(
                version,
                source,
                result.status().name(),
                parsed.accepted().size(),
                parsed.rejected().size(),
                List.copyOf(parsed.rejected()),
                parsed.conflicts().size(),
                List.copyOf(parsed.conflicts()),
                result.status() == SnapshotStore.ImportStatus.DUPLICATE,
                pinnedId);

        log.info("import result: version={} source={} status={} pinnedRules={} accepted={} rejected={} conflicts={} reasons={}",
                version, source, result.status(), pinnedId, report.acceptedCount(),
                report.rejectedCount(), report.conflictCount(),
                tally(report.rejected(), report.conflicts()));
        return report;
    }

    private static String tally(List<RejectedRecord> rejected, List<RejectedRecord> conflicts) {
        java.util.Map<String, Integer> counts = new java.util.TreeMap<>();
        rejected.forEach(r -> counts.merge("reject:" + r.reason(), 1, Integer::sum));
        conflicts.forEach(r -> counts.merge("conflict:" + r.reason(), 1, Integer::sum));
        return counts.toString();
    }

    @Override
    public boolean switchVersion(String version) {
        boolean ok = store.switchVersion(version);
        log.info("switchVersion: requested={} outcome={}", version, ok);
        return ok;
    }

    @Override
    public Optional<String> currentVersion() {
        return store.currentVersion();
    }

    @Override
    public List<String> versions() {
        return store.versions();
    }

    @Override
    public Optional<Snapshot> getSnapshot(String version) {
        return store.view(version).map(v -> new Snapshot(
                version, v.records(), v.rejected(), v.conflicts()));
    }

    @Override
    public Optional<ResolutionReport> getResolution(String version) {
        return store.view(version).map(SnapshotStore.SnapshotView::resolution);
    }

    @Override
    public ResolutionReport getResolution(String version, String ruleSetId) {
        RuleSet ruleSet = ruleSetRegistry.resolve(ruleSetId);
        return store.resolutionUnder(version, ruleSet);
    }

    @Override
    public ResolutionComparison compareResolutions(String version, String ruleSetA,
                                                   String ruleSetB) {
        ResolutionReport a = getResolution(version, ruleSetA);
        ResolutionReport b = getResolution(version, ruleSetB);
        ResolutionComparison comparisonResult = comparison.compare(a, b);
        log.info("resolution comparison: version={} rulesA={} rulesB={} equivalent={} "
                        + "clusterDiffs={} fieldDiffs={} conflictsOnlyA={} conflictsOnlyB={} "
                        + "ambiguitiesOnlyA={} ambiguitiesOnlyB={}",
                version, ruleSetA, ruleSetB, comparisonResult.equivalent(),
                comparisonResult.clusterDifferences().size(),
                comparisonResult.fieldDifferences().size(),
                comparisonResult.conflictsOnlyUnderA().size(),
                comparisonResult.conflictsOnlyUnderB().size(),
                comparisonResult.ambiguitiesOnlyUnderA().size(),
                comparisonResult.ambiguitiesOnlyUnderB().size());
        return comparisonResult;
    }

    /**
     * Resolve an identifier against the current version's <b>pinned</b> rule set.
     * The captured view is fixed for the whole lookup, so a rule switch in another thread
     * can never produce a half-old/half-new answer.
     */
    @Override
    public Optional<ResolvedEntity> resolveIdentifier(String identifier) {
        return resolveIdentifier(identifier, null);
    }

    @Override
    public Optional<ResolvedEntity> resolveIdentifier(String identifier, String ruleSetId) {
        if (identifier == null || identifier.isBlank()) {
            return Optional.empty();
        }
        Optional<SnapshotStore.SnapshotView> viewOpt = store.currentView();
        if (viewOpt.isEmpty()) {
            return Optional.empty();
        }
        SnapshotStore.SnapshotView view = viewOpt.get();
        RuleSet ruleSet = ruleSetId == null || ruleSetId.isBlank()
                ? view.pinnedRules() : ruleSetRegistry.resolve(ruleSetId);
        ResolutionReport report = ruleSet.id().equals(view.pinnedRules().id())
                ? view.pinnedResolution()
                : store.resolutionUnder(view.pinnedResolution().snapshotVersion(), ruleSet);

        String needle = identifier.trim();
        for (ResolvedEntity e : report.entities()) {
            if (matches(e, needle)) {
                log.info("identifier resolved: version={} rules={} query={} entityKey={} sources={}",
                        report.snapshotVersion(), report.rulesVersion(),
                        needle, e.entityKey(), e.identifiersBySource());
                return Optional.of(e);
            }
        }
        log.info("identifier unresolved: version={} rules={} query={}",
                report.snapshotVersion(), report.rulesVersion(), needle);
        return Optional.empty();
    }

    private static boolean matches(ResolvedEntity e, String needle) {
        if (e.entityKey().equals(needle)) {
            return true;
        }
        for (var entry : e.identifiersBySource().entrySet()) {
            if (entry.getValue().equals(needle)
                    || (entry.getKey() + ":" + entry.getValue()).equals(needle)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public List<ResolvedEntity> queryAll() {
        return queryAll((String) null);
    }

    @Override
    public List<ResolvedEntity> queryAll(String ruleSetId) {
        Optional<SnapshotStore.SnapshotView> viewOpt = store.currentView();
        if (viewOpt.isEmpty()) {
            return List.of();
        }
        SnapshotStore.SnapshotView view = viewOpt.get();
        if (ruleSetId == null || ruleSetId.isBlank()
                || ruleSetId.equals(view.pinnedRules().id())) {
            return view.resolution().entities();
        }
        RuleSet ruleSet = ruleSetRegistry.resolve(ruleSetId);
        return store.resolutionUnder(view.pinnedResolution().snapshotVersion(), ruleSet)
                .entities();
    }

    @Override
    public List<RuleSet> listRuleSets() {
        return ruleSetRegistry.all();
    }

    @Override
    public Optional<RuleSet> activeRuleSet() {
        return ruleSetRegistry.active();
    }

    @Override
    public boolean activateRuleSet(String ruleSetId) {
        boolean activated = ruleSetRegistry.activate(ruleSetId);
        if (activated) {
            RuleSet ruleSet = ruleSetRegistry.requireActive();
            store.activateRuleSet(ruleSet);
            log.info("rule set activated for future publications: {}", ruleSetId);
        } else {
            log.warn("rule set activation refused, unknown id: {}", ruleSetId);
        }
        return activated;
    }

    @Override
    public RuleSetReloadResult reloadRuleSets() {
        RuleSetReloadResult result = ruleSetRegistry.reload();
        ruleSetRegistry.active().ifPresent(store::activateRuleSet);
        log.info("rule sets reloaded: active={} loaded={} refusals={}",
                result.activeRuleSetId(), result.loadedIds(),
                result.failures().stream().map(f -> f.origin() + ":" + f.reason()).toList());
        return result;
    }

    static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
