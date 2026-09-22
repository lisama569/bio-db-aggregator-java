package com.github.lisama569.biodbaggregatorjava.snapshot;

import com.github.lisama569.biodbaggregatorjava.model.BioRecord;
import com.github.lisama569.biodbaggregatorjava.model.EntityType;
import com.github.lisama569.biodbaggregatorjava.model.RejectReason;
import com.github.lisama569.biodbaggregatorjava.model.RejectedRecord;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 把各格式解析出的字段行归一为统一记录，并做必填校验。纯静态工具，无状态。
 *
 * <p>归一约定：
 * <ul>
 *   <li>type 仅接受 gene/protein（大小写不敏感），未知类型拒绝；</li>
 *   <li>必填：source 主键 primaryId、至少一个标识符；</li>
 *   <li>标识符字段命名：geneId、proteinId、refseq、uniprot、ensembl 及
 *       任意形如 ns:value 的 identifiers 条目；symbol 作为弱标识符一并收录；</li>
 *   <li>空字符串视为缺失，不得静默当作合法值。</li>
 * </ul>
 */
public final class RecordNormalizer {

    /** 已知的标识符列 -> 规范命名空间。 */
    private static final Map<String, String> ID_COLUMNS = Map.of(
            "geneid", "geneId",
            "proteinid", "proteinId",
            "refseq", "refseq",
            "uniprot", "uniprot",
            "ensembl", "ensembl",
            "ncbi", "ncbi"
    );

    private RecordNormalizer() {
    }

    /**
     * 归一一条记录；任何字段问题都返回 {@link NormalizeOutcome.Rejected}，
     * 原因可区分，不静默丢弃。
     *
     * @param source      来源名称
     * @param typeRaw     原始类型字符串
     * @param primaryId   主键列（id）
     * @param rawIds      原始字段映射（列名 -> 值），由各格式解析器提供，可为 null
     * @param symbol      符号列
     * @param name        名称列
     * @param sequence    序列列
     * @param ordinal     来源内行序号（从 1 开始）
     * @param rawLine     原始行文本（拒绝时保留）
     */
    public static NormalizeOutcome normalize(
            String source,
            String typeRaw,
            String primaryId,
            Map<String, String> rawIds,
            String symbol,
            String name,
            String sequence,
            long ordinal,
            String rawLine
    ) {
        String typeStr = trimToNull(typeRaw);
        String id = trimToNull(primaryId);
        String sym = trimToNull(symbol);

        if (typeStr == null) {
            return reject(source, ordinal, rawLine,
                    RejectReason.MISSING_REQUIRED_FIELD, "缺少必填字段 type");
        }
        final EntityType type;
        switch (typeStr.toLowerCase(Locale.ROOT)) {
            case "gene" -> type = EntityType.GENE;
            case "protein" -> type = EntityType.PROTEIN;
            default -> {
                return reject(source, ordinal, rawLine,
                        RejectReason.MISSING_REQUIRED_FIELD,
                        "未知实体类型: '" + typeStr + "'（仅支持 gene/protein）");
            }
        }

        if (id == null) {
            return reject(source, ordinal, rawLine,
                    RejectReason.MISSING_REQUIRED_FIELD, "缺少必填字段 id（来源内主键）");
        }

        Map<String, String> identifiers = new HashMap<>();
        if (rawIds != null) {
            for (Map.Entry<String, String> e : rawIds.entrySet()) {
                String column = e.getKey() == null ? "" : e.getKey().trim();
                String value = trimToNull(e.getValue());
                if (value == null || column.isEmpty()) {
                    continue;
                }
                String lc = column.toLowerCase(Locale.ROOT);
                if (ID_COLUMNS.containsKey(lc)) {
                    identifiers.put(ID_COLUMNS.get(lc), value);
                } else if (column.contains(":")) {
                    // 支持 "ns:value" 形式的自由标识符列
                    int idx = column.indexOf(':');
                    String ns = column.substring(0, idx).trim();
                    if (!ns.isEmpty()) {
                        identifiers.put(ns, value);
                    }
                }
            }
        }
        // 来源自身命名空间保证存在：主键即来源内标识
        identifiers.putIfAbsent("local:" + source, id);
        if (sym != null) {
            identifiers.putIfAbsent("symbol", sym);
        }

        if (identifiers.size() <= 1) {
            return reject(source, ordinal, rawLine,
                    RejectReason.MISSING_REQUIRED_FIELD,
                    "记录缺少任何跨源标识符（geneId/proteinId/refseq/uniprot/ensembl/symbol 等）");
        }

        BioRecord record = new BioRecord(
                source,
                type,
                id,
                identifiers,
                sym,
                trimToNull(name),
                trimToNull(sequence),
                ordinal
        );
        return new NormalizeOutcome.Accepted(record);
    }

    private static NormalizeOutcome.Rejected reject(String source, long ordinal, String rawLine,
                                                    RejectReason reason, String detail) {
        return new NormalizeOutcome.Rejected(new RejectedRecord(
                source, (int) ordinal, rawLine == null ? "" : rawLine, reason, detail));
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
