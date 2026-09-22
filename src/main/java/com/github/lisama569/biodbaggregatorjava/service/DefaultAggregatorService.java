package com.github.lisama569.biodbaggregatorjava.service;

import com.github.lisama569.biodbaggregatorjava.model.BioRecord;
import com.github.lisama569.biodbaggregatorjava.model.ImportReport;
import com.github.lisama569.biodbaggregatorjava.model.RejectedRecord;
import com.github.lisama569.biodbaggregatorjava.model.ResolutionReport;
import com.github.lisama569.biodbaggregatorjava.model.ResolvedEntity;
import com.github.lisama569.biodbaggregatorjava.model.SourceFormat;
import com.github.lisama569.biodbaggregatorjava.model.Snapshot;
import com.github.lisama569.biodbaggregatorjava.parser.ParserRegistry;
import com.github.lisama569.biodbaggregatorjava.parser.SnapshotParser;
import com.github.lisama569.biodbaggregatorjava.store.SnapshotStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/** Default facade coordinating parsing, idempotent publishing and consistent reads. */
@Service
public class DefaultAggregatorService implements AggregatorService {

    private static final Logger log = LoggerFactory.getLogger(DefaultAggregatorService.class);

    private final ParserRegistry parserRegistry;
    private final SnapshotStore store;

    public DefaultAggregatorService(ParserRegistry parserRegistry, SnapshotStore store) {
        this.parserRegistry = parserRegistry;
        this.store = store;
    }

    @Override
    public ImportReport importSnapshot(String version, String source, SourceFormat format, String rawInput) {
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException("snapshot version must not be blank");
        }
        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException("source must not be blank");
        }
        if (format == null) {
            throw new IllegalArgumentException("source format must not be null");
        }
        String content = rawInput == null ? "" : rawInput;
        log.info("import request: version={} source={} format={} bytes={}",
                version, source, format, content.length());

        SnapshotParser parser = parserRegistry.parserFor(format);
        SnapshotParser.ParseResult parsed = parser.parse(source, content);
        String hash = sha256(version + "|" + source + "|" + format + "|" + content);

        SnapshotStore.SourceImport input = new SnapshotStore.SourceImport(
                version, source, format, hash,
                List.copyOf(parsed.accepted()),
                List.copyOf(parsed.rejected()),
                List.copyOf(parsed.conflicts()));
        SnapshotStore.PublishResult result = store.importSource(input);

        ImportReport report = new ImportReport(
                version,
                source,
                result.status().name(),
                parsed.accepted().size(),
                parsed.rejected().size(),
                List.copyOf(parsed.rejected()),
                parsed.conflicts().size(),
                List.copyOf(parsed.conflicts()),
                result.status() == SnapshotStore.ImportStatus.DUPLICATE);

        log.info("import result: version={} source={} status={} accepted={} rejected={} conflicts={} reasons={}",
                version, source, result.status(), report.acceptedCount(), report.rejectedCount(),
                report.conflictCount(), tally(report.rejected(), report.conflicts()));
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

    /**
     * Resolve an identifier against the current version only. The identifier may be a
     * source-local id (e.g. {@code P12345}), a namespaced id ({@code uniprot:P12345}) or
     * an entity key. The captured view is fixed for the whole lookup.
     */
    @Override
    public Optional<ResolvedEntity> resolveIdentifier(String identifier) {
        if (identifier == null || identifier.isBlank()) {
            return Optional.empty();
        }
        Optional<SnapshotStore.SnapshotView> viewOpt = store.currentView();
        if (viewOpt.isEmpty()) {
            return Optional.empty();
        }
        SnapshotStore.SnapshotView view = viewOpt.get();
        String needle = identifier.trim();
        for (ResolvedEntity e : view.resolution().entities()) {
            if (matches(e, needle)) {
                log.info("identifier resolved: version={} rules={} query={} entityKey={} sources={}",
                        view.resolution().snapshotVersion(), view.resolution().rulesVersion(),
                        needle, e.entityKey(), e.identifiersBySource());
                return Optional.of(e);
            }
        }
        log.info("identifier unresolved: version={} query={}", view.resolution().snapshotVersion(), needle);
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
        return store.currentView()
                .map(v -> v.resolution().entities())
                .orElse(List.of());
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
