package com.github.lisama569.biodbaggregatorjava.snapshot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.lisama569.biodbaggregatorjava.model.BioRecord;
import com.github.lisama569.biodbaggregatorjava.model.RejectReason;
import com.github.lisama569.biodbaggregatorjava.model.RejectedRecord;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JSONL 快照解析器：每行一个 JSON 对象，字段：
 * {@code id,type,geneId,proteinId,refseq,uniprot,ensembl,symbol,name,sequence,identifiers}。
 *
 * <p>任何单行解析失败都归类为 MALFORMED_RECORD 并保留原文；
 * 字段语义问题交由 {@link RecordNormalizer} 归类，坏记录不阻断后续行。
 */
@Component
public class JsonlSnapshotParser implements SnapshotParser {

    private static final List<String> FIXED_ID_COLUMNS =
            List.of("geneId", "proteinId", "refseq", "uniprot", "ensembl", "ncbi");

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public boolean supports(SourceFormat format) {
        return format == SourceFormat.JSONL;
    }

    @Override
    public SourceParseResult parse(SnapshotInput input) {
        List<BioRecord> accepted = new ArrayList<>();
        List<RejectedRecord> rejected = new ArrayList<>();
        String source = input.source();

        String[] lines = input.content().split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String rawLine = lines[i];
            String trimmed = rawLine.strip();
            if (trimmed.isEmpty()) {
                continue;
            }
            int lineNo = i + 1;
            final JsonNode node;
            try {
                node = mapper.readTree(trimmed);
            } catch (Exception ex) {
                rejected.add(new RejectedRecord(source, lineNo, rawLine,
                        RejectReason.MALFORMED_RECORD, "JSON 解析失败: " + ex.getMessage()));
                continue;
            }
            if (node == null || !node.isObject()) {
                rejected.add(new RejectedRecord(source, lineNo, rawLine,
                        RejectReason.MALFORMED_RECORD, "每行必须是一个 JSON 对象"));
                continue;
            }

            final String typeText;
            final String idText;
            final String symbolText;
            final String nameText;
            final String sequenceText;
            final Map<String, String> rawIds = new LinkedHashMap<>();
            try {
                typeText = text(node, "type");
                idText = text(node, "id");
                symbolText = text(node, "symbol");
                nameText = text(node, "name");
                sequenceText = text(node, "sequence");
                for (String col : FIXED_ID_COLUMNS) {
                    JsonNode v = node.get(col);
                    if (v != null && !v.isNull()) {
                        if (!v.isValueNode()) {
                            throw new IllegalArgumentException("标识符字段 " + col + " 必须是标量值");
                        }
                        rawIds.put(col, v.asText());
                    }
                }
                JsonNode ids = node.get("identifiers");
                if (ids != null && ids.isObject()) {
                    Iterator<Map.Entry<String, JsonNode>> it = ids.fields();
                    while (it.hasNext()) {
                        Map.Entry<String, JsonNode> e = it.next();
                        if (!e.getValue().isNull() && e.getValue().isValueNode()) {
                            rawIds.put(e.getKey(), e.getValue().asText());
                        }
                    }
                }
            } catch (IllegalArgumentException ex) {
                rejected.add(new RejectedRecord(source, lineNo, rawLine,
                        RejectReason.MALFORMED_RECORD, ex.getMessage()));
                continue;
            }

            NormalizeOutcome outcome = RecordNormalizer.normalize(
                    source, typeText, idText, rawIds,
                    symbolText, nameText, sequenceText, lineNo, rawLine);
            if (outcome instanceof NormalizeOutcome.Accepted a) {
                accepted.add(a.record());
            } else if (outcome instanceof NormalizeOutcome.Rejected r) {
                rejected.add(r.rejected());
            }
        }
        return new SourceParseResult(source, List.copyOf(accepted), List.copyOf(rejected));
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        if (v.isValueNode()) {
            return v.asText();
        }
        throw new IllegalArgumentException("字段 " + field + " 必须是标量值");
    }
}
