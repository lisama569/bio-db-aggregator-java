package com.github.lisama569.biodbaggregatorjava.snapshot;

import com.github.lisama569.biodbaggregatorjava.model.BioRecord;
import com.github.lisama569.biodbaggregatorjava.model.RejectReason;
import com.github.lisama569.biodbaggregatorjava.model.RejectedRecord;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 来源内主键冲突检测。
 *
 * <p>对单个来源的归一结果按 {@code source:type:id} 分组：
 * <ul>
 *   <li>内容完全相同的重复记录：不追加拒绝（解析器内部会幂等去重，不产生计数漂移）；</li>
 *   <li>同一主键内容不一致：对每条记录追加一条
 *       {@link RejectReason#WITHIN_SOURCE_ID_CONFLICT} 拒绝记录（原因可区分、含行号）。
 *       记录本身仍全部进入跨源解析，由 {@code IdentifierResolver}
 *       把对应实体标记为 AMBIGUOUS——不静默取其一，也不静默丢弃。</li>
 * </ul>
 */
public final class WithinSourceConflictDetector {

    private WithinSourceConflictDetector() {
    }

    public static SourceParseResult detect(SourceParseResult parsed) {
        Map<String, List<BioRecord>> byKey = new LinkedHashMap<>();
        for (BioRecord r : parsed.accepted()) {
            byKey.computeIfAbsent(r.recordKey(), k -> new ArrayList<>()).add(r);
        }

        List<RejectedRecord> rejected = new ArrayList<>(parsed.rejected());
        for (Map.Entry<String, List<BioRecord>> e : byKey.entrySet()) {
            List<BioRecord> group = e.getValue();
            if (group.size() < 2) {
                continue;
            }
            BioRecord first = group.get(0);
            boolean identical = group.stream().allMatch(r -> sameContent(first, r));
            if (identical) {
                continue;
            }
            for (BioRecord r : group) {
                rejected.add(new RejectedRecord(
                        r.source(),
                        (int) r.ordinal(),
                        "sourceKey=" + r.recordKey(),
                        RejectReason.WITHIN_SOURCE_ID_CONFLICT,
                        "来源 " + r.source() + " 内主键 " + r.primaryId()
                                + " 存在 " + group.size() + " 条内容不一致的记录（行: "
                                + group.stream().map(x -> String.valueOf(x.ordinal())).toList()
                                + "），实体标记为 AMBIGUOUS，不静默取其一"));
            }
        }
        return new SourceParseResult(parsed.source(), parsed.accepted(), List.copyOf(rejected));
    }

    private static boolean sameContent(BioRecord a, BioRecord b) {
        return a.identifiers().equals(b.identifiers())
                && nullSafeEq(a.symbol(), b.symbol())
                && nullSafeEq(a.name(), b.name())
                && nullSafeEq(a.sequence(), b.sequence());
    }

    private static boolean nullSafeEq(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }
}
