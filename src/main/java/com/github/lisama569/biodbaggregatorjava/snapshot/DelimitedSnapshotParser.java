package com.github.lisama569.biodbaggregatorjava.snapshot;

import com.github.lisama569.biodbaggregatorjava.model.BioRecord;
import com.github.lisama569.biodbaggregatorjava.model.RejectReason;
import com.github.lisama569.biodbaggregatorjava.model.RejectedRecord;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CSV / TSV 快照解析器（自带最小引号转义处理，不引入外部依赖）。
 *
 * <p>首行为表头，支持列：
 * {@code id,type,geneId,proteinId,refseq,uniprot,ensembl,symbol,name,sequence}。
 * 支持引号包裹与双引号转义（"a""b"）；\r\n 行尾、行内换行（引号内）均支持。
 * 列数与表头不匹配的行归类为 MALFORMED_RECORD，不阻断后续行。
 */
@Component
public class DelimitedSnapshotParser implements SnapshotParser {

    @Override
    public boolean supports(SourceFormat format) {
        return format == SourceFormat.CSV || format == SourceFormat.TSV;
    }

    @Override
    public SourceParseResult parse(SnapshotInput input) {
        char delim = input.format() == SourceFormat.TSV ? '\t' : ',';
        List<BioRecord> accepted = new ArrayList<>();
        List<RejectedRecord> rejected = new ArrayList<>();
        String source = input.source();

        CsvLineReader reader = new CsvLineReader(input.content());
        // 读取表头（可能跨物理行）
        ParsedLine headerLine = reader.nextLine(delim);
        if (headerLine == null) {
            rejected.add(new RejectedRecord(source, 0, "",
                    RejectReason.MALFORMED_RECORD, "快照为空，缺少表头行"));
            return new SourceParseResult(source, List.of(), List.copyOf(rejected));
        }
        if (headerLine.parseError() != null) {
            rejected.add(new RejectedRecord(source, headerLine.firstPhysicalLine(),
                    headerLine.raw(), RejectReason.MALFORMED_RECORD,
                    "表头解析失败: " + headerLine.parseError()));
            return new SourceParseResult(source, List.of(), List.copyOf(rejected));
        }
        List<String> headers = headerLine.fields();
        int idIdx = indexOf(headers, "id");
        int typeIdx = indexOf(headers, "type");
        if (idIdx < 0 || typeIdx < 0) {
            rejected.add(new RejectedRecord(source, headerLine.firstPhysicalLine(),
                    headerLine.raw(), RejectReason.MISSING_REQUIRED_FIELD,
                    "表头必须包含 id 与 type 列，实际: " + headers));
            return new SourceParseResult(source, List.of(), List.copyOf(rejected));
        }

        ParsedLine row;
        while ((row = reader.nextLine(delim)) != null) {
            // 跳过纯空白物理行
            if (row.fields().size() == 1
                    && row.fields().get(0).strip().isEmpty()
                    && row.parseError() == null
                    && !row.raw().contains("\"")) {
                continue;
            }
            int lineNo = row.firstPhysicalLine();
            if (row.parseError() != null) {
                rejected.add(new RejectedRecord(source, lineNo, row.raw(),
                        RejectReason.MALFORMED_RECORD, row.parseError()));
                continue;
            }
            if (row.fields().size() != headers.size()) {
                rejected.add(new RejectedRecord(source, lineNo, row.raw(),
                        RejectReason.MALFORMED_RECORD,
                        "列数 " + row.fields().size() + " 与表头列数 " + headers.size() + " 不一致"));
                continue;
            }
            Map<String, String> rawIds = new LinkedHashMap<>();
            for (int c = 0; c < headers.size(); c++) {
                String h = headers.get(c).trim();
                if (!h.equals("id") && !h.equals("type") && !h.equals("name")
                        && !h.equals("sequence")) {
                    // symbol 也作为弱标识符传入归一器
                    rawIds.put(h, row.fields().get(c));
                }
            }
            NormalizeOutcome outcome = RecordNormalizer.normalize(
                    source,
                    row.fields().get(typeIdx),
                    row.fields().get(idIdx),
                    rawIds,
                    rawIds.get("symbol"),
                    cell(row, headers, "name"),
                    cell(row, headers, "sequence"),
                    lineNo,
                    row.raw());
            if (outcome instanceof NormalizeOutcome.Accepted a) {
                accepted.add(a.record());
            } else if (outcome instanceof NormalizeOutcome.Rejected r) {
                rejected.add(r.rejected());
            }
        }
        return new SourceParseResult(source, List.copyOf(accepted), List.copyOf(rejected));
    }

    private static String cell(ParsedLine row, List<String> headers, String name) {
        int idx = indexOf(headers, name);
        return idx < 0 ? null : row.fields().get(idx);
    }

    private static int indexOf(List<String> headers, String name) {
        for (int i = 0; i < headers.size(); i++) {
            if (headers.get(i).trim().equalsIgnoreCase(name)) {
                return i;
            }
        }
        return -1;
    }

    /** 一条逻辑记录（可能跨多个物理行）。 */
    private record ParsedLine(List<String> fields, String raw,
                              int firstPhysicalLine, String parseError) {
    }

    /**
     * 最小 CSV 行读取器：支持引号包裹、双引号转义、引号内换行；
     * 引号未闭合视为解析错误。
     */
    private static final class CsvLineReader {
        private final String content;
        private int pos;
        private int physicalLine = 1;

        CsvLineReader(String content) {
            this.content = content == null ? "" : content;
        }

        ParsedLine nextLine(char delim) {
            if (pos >= content.length()) {
                return null;
            }
            int firstLine = physicalLine;
            int rawStart = pos;
            List<String> fields = new ArrayList<>();
            StringBuilder field = new StringBuilder();
            boolean inQuotes = false;
            boolean startedQuotedField = false;
            boolean hasContent = false;

            while (pos < content.length()) {
                char c = content.charAt(pos);
                if (c == '\n') {
                    physicalLine++;
                }
                if (inQuotes) {
                    if (c == '"') {
                        if (pos + 1 < content.length() && content.charAt(pos + 1) == '"') {
                            field.append('"');
                            pos += 2;
                            continue;
                        }
                        inQuotes = false;
                        pos++;
                        continue;
                    }
                    field.append(c);
                    pos++;
                    continue;
                }
                if (c == '"' && field.length() == 0 && !startedQuotedField && !hasContent) {
                    inQuotes = true;
                    startedQuotedField = true;
                    hasContent = true;
                    pos++;
                    continue;
                }
                if (c == delim) {
                    fields.add(field.toString());
                    field.setLength(0);
                    startedQuotedField = false;
                    hasContent = false;
                    pos++;
                    continue;
                }
                if (c == '\n') {
                    pos++;
                    fields.add(field.toString());
                    String raw = content.substring(rawStart, pos);
                    return new ParsedLine(List.copyOf(fields), stripTrailingCr(raw), firstLine, null);
                }
                if (c == '\r') {
                    // 单独 \r 或 \r\n
                    if (pos + 1 < content.length() && content.charAt(pos + 1) == '\n') {
                        pos += 2;
                        physicalLine++;
                    } else {
                        pos++;
                        physicalLine++;
                    }
                    fields.add(field.toString());
                    String raw = content.substring(rawStart, pos);
                    return new ParsedLine(List.copyOf(fields), stripTrailingCr(raw), firstLine, null);
                }
                if (c == '"') {
                    // 出现在非引号字段中间：非法
                    int endLine = pos;
                    while (endLine < content.length() && content.charAt(endLine) != '\n') {
                        endLine++;
                    }
                    return new ParsedLine(List.of(),
                            content.substring(rawStart, Math.min(endLine + 1, content.length())),
                            firstLine, "未转义的引号出现在非引号字段中");
                }
                field.append(c);
                hasContent = true;
                pos++;
            }
            // 文件结尾无换行
            if (inQuotes) {
                return new ParsedLine(List.of(),
                        content.substring(rawStart), firstLine, "引号未闭合");
            }
            fields.add(field.toString());
            return new ParsedLine(List.copyOf(fields),
                    content.substring(rawStart), firstLine, null);
        }
    }

    private static String stripTrailingCr(String raw) {
        return raw.endsWith("\r\n") ? raw.substring(0, raw.length() - 2)
                : raw.endsWith("\n") ? raw.substring(0, raw.length() - 1)
                : raw.endsWith("\r") ? raw.substring(0, raw.length() - 1)
                : raw;
    }
}
