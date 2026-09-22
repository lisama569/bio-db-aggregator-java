package com.github.lisama569.biodbaggregatorjava.service;

import com.github.lisama569.biodbaggregatorjava.model.BioRecord;
import com.github.lisama569.biodbaggregatorjava.model.ImportReport;
import com.github.lisama569.biodbaggregatorjava.model.RejectedRecord;
import com.github.lisama569.biodbaggregatorjava.resolve.IdentifierResolver;
import com.github.lisama569.biodbaggregatorjava.resolve.ResolutionResult;
import com.github.lisama569.biodbaggregatorjava.resolve.ResolutionRules;
import com.github.lisama569.biodbaggregatorjava.snapshot.Snapshot;
import com.github.lisama569.biodbaggregatorjava.snapshot.WithinSourceConflictDetector;
import com.github.lisama569.biodbaggregatorjava.snapshot.SnapshotInput;
import com.github.lisama569.biodbaggregatorjava.snapshot.SnapshotParser;
import com.github.lisama569.biodbaggregatorjava.snapshot.SourceFormat;
import com.github.lisama569.biodbaggregatorjava.snapshot.SourceParseResult;
import com.github.lisama569.biodbaggregatorjava.store.SnapshotStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.security.MessageDigest;

/**
 * 快照导入器：解析 -> 归一 -> 跨源解析 -> 组装不可变快照 -> 幂等安装。
 *
 * <p>版本与复现性：
 * <ul>
 *   <li>版本号 = {@code snapshot-<序号>}，序号为内容哈希前 12 位之外的单调计数；
 *       实际唯一性由内容哈希保证；</li>
 *   <li>内容哈希覆盖规则版本 + 全部输入（来源名、格式、内容），
 *       规则或输入任何一项变化都会产生新版本；</li>
 *   <li>同一内容重复导入走 {@link SnapshotStore#install} 的内容寻址复用，
 *       不产生重复记录或计数漂移。</li>
 * </ul>
 */
@Service
public class SnapshotImporter {

    private static final Logger log = LoggerFactory.getLogger(SnapshotImporter.class);

    private final List<SnapshotParser> parsers;
    private final IdentifierResolver resolver;
    private final SnapshotStore store;
    private final ResolutionRules rules;
    private long sequence;

    public SnapshotImporter(List<SnapshotParser> parsers,
                            IdentifierResolver resolver,
                            SnapshotStore store,
                            ResolutionRules rules) {
        this.parsers = parsers;
        this.resolver = resolver;
        this.store = store;
        this.rules = rules;
    }

    /**
     * 导入一组来源快照。
     *
     * @param inputs 多个来源的本地快照
     * @return 导入报告（含被拒绝记录与可区分原因）
     */
    public synchronized ImportReport importSnapshot(List<SnapshotInput> inputs) {
        // 输入排序保证哈希确定性
        List<SnapshotInput> ordered = inputs.stream()
                .sorted(Comparator.comparing(SnapshotInput::source)).toList();

        StringBuilder hashInput = new StringBuilder("rules=").append(rules.rulesVersion());
        List<BioRecord> allRecords = new ArrayList<>();
        List<RejectedRecord> allRejected = new ArrayList<>();

        for (SnapshotInput in : ordered) {
            hashInput.append('\n').append(in.source())
                   .append('|').append(in.format()).append('|').append(in.content());
            log.info("[snapshot-import] source={} format={} bytes={}",
                    in.source(), in.format(), in.content().length());

            SnapshotParser parser = parsers.stream()
                    .filter(p -> p.supports(in.format()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(
                            "不支持的来源格式: " + in.format()));
            SourceParseResult parsed = WithinSourceConflictDetector.detect(parser.parse(in));
            allRecords.addAll(parsed.accepted());
            allRejected.addAll(parsed.rejected());
            parsed.rejected().forEach(r -> log.info(
                    "[snapshot-import] REJECTED source={} line={} reason={} detail={} raw={}",
                    r.source(), r.lineNumber(), r.reason(), r.detail(),
                    abbreviate(r.rawLine())));
        }

        String hash = sha256Hex(hashInput.toString());
        // 全局确定顺序：来源优先级 -> 来源名 -> 类型 -> 行号
        Map<String, Integer> sourceOrder = new LinkedHashMap<>();
        rules.sourcePriority().forEach(s -> sourceOrder.put(s, sourceOrder.size()));
        List<BioRecord> sortedRecords = allRecords.stream()
                .sorted(Comparator
                        .comparingInt((BioRecord r) ->
                                sourceOrder.getOrDefault(r.source(), Integer.MAX_VALUE))
                        .thenComparing(BioRecord::source)
                        .thenComparing(r -> r.type().name())
                        .thenComparingLong(BioRecord::ordinal)
                        .thenComparing(BioRecord::primaryId))
                .toList();
        List<RejectedRecord> sortedRejected = allRejected.stream()
                .sorted(Comparator.comparing(RejectedRecord::source)
                        .thenComparingInt(RejectedRecord::lineNumber))
                .toList();

        ResolutionResult resolution = resolver.resolve(sortedRecords, rules);

        String version = "snapshot-" + (++sequence) + "-" + hash.substring(0, 12);
        Snapshot snapshot = new Snapshot(
                version,
                hash,
                List.copyOf(sortedRecords),
                List.copyOf(sortedRejected),
                resolution.entitiesByCanonicalId(),
                resolution.canonicalIdByIdentifier());

        SnapshotStore.InstallOutcome outcome = store.install(snapshot);
        if (outcome.idempotentReuse()) {
            // 序号回退，保证重复导入不制造序号漂移
            sequence--;
        }

        log.info("[snapshot-import] version={} contentHash={} idempotentReuse={} "
                        + "imported={} rejected={} ambiguous={} conflicts={}",
                outcome.snapshot().version(), hash, outcome.idempotentReuse(),
                sortedRecords.size(), sortedRejected.size(),
                resolution.ambiguousCount(), resolution.conflictCount());

        return new ImportReport(
                outcome.snapshot().version(),
                hash,
                sortedRecords.size(),
                sortedRejected.size(),
                resolution.conflictCount() + resolution.ambiguousCount(),
                outcome.idempotentReuse(),
                List.copyOf(sortedRejected));
    }

    /** 当前规则版本（测试与文档校验用）。 */
    public ResolutionRules rules() {
        return rules;
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String abbreviate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= 200 ? s : s.substring(0, 200) + "...";
    }
}
