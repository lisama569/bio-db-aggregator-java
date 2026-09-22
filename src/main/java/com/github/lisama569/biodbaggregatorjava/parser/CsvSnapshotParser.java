package com.github.lisama569.biodbaggregatorjava.parser;

import com.github.lisama569.biodbaggregatorjava.model.BioRecord;
import com.github.lisama569.biodbaggregatorjava.model.RejectedRecord;
import com.github.lisama569.biodbaggregatorjava.model.RejectionReason;
import com.github.lisama569.biodbaggregatorjava.model.SourceFormat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parser for CSV snapshot exports. Required header (first non-comment line):
 * <pre>id,type,symbol,name,sequence,organism,xrefs</pre>
 * Lines beginning with '#' are comments; blank lines are ignored. The xrefs column
 * carries semicolon-separated namespace:value pairs.
 */
@org.springframework.stereotype.Component
public class CsvSnapshotParser implements SnapshotParser {

    static final List<String> EXPECTED_HEADER =
            List.of("id", "type", "symbol", "name", "sequence", "organism", "xrefs");

    @Override
    public SourceFormat format() {
        return SourceFormat.CSV;
    }

    @Override
    public ParseResult parse(String source, String rawInput) {
        List<BioRecord> accepted = new ArrayList<>();
        List<RejectedRecord> rejected = new ArrayList<>();
        List<RejectedRecord> conflicts = new ArrayList<>();
        Map<String, Integer> seenIds = new LinkedHashMap<>();

        String[] lines = rawInput.split("\\R", -1);
        int dataLineNo = 0;
        boolean headerSeen = false;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.isBlank() || line.stripLeading().startsWith("#")) {
                continue;
            }
            List<String> cols;
            try {
                cols = splitCsv(line);
            } catch (IllegalArgumentException e) {
                rejected.add(new RejectedRecord(source, line, RejectionReason.MALFORMED_SYNTAX,
                        "line " + (i + 1) + ": " + e.getMessage()));
                continue;
            }

            if (!headerSeen) {
                headerSeen = true;
                if (!normalizeHeader(cols).equals(EXPECTED_HEADER)) {
                    rejected.add(new RejectedRecord(source, line, RejectionReason.MALFORMED_SYNTAX,
                            "line " + (i + 1) + ": invalid CSV header, expected " + EXPECTED_HEADER
                                    + " but got " + cols));
                    return new ParseResult(accepted, rejected, conflicts);
                }
                continue;
            }

            dataLineNo++;
            if (cols.size() != EXPECTED_HEADER.size()) {
                rejected.add(new RejectedRecord(source, line, RejectionReason.MALFORMED_SYNTAX,
                        "line " + (i + 1) + ": expected " + EXPECTED_HEADER.size()
                                + " columns but got " + cols.size()));
                continue;
            }
            Map<String, String> xrefs;
            try {
                xrefs = RecordSupport.parseXrefList(cols.get(6));
            } catch (RecordSupport.InvalidXrefException e) {
                rejected.add(new RejectedRecord(source, line, RejectionReason.MALFORMED_SYNTAX,
                        "line " + (i + 1) + ": " + e.getMessage()));
                continue;
            }
            RecordSupport.RawValues values = new RecordSupport.RawValues(
                    cols.get(0), cols.get(1), cols.get(2), cols.get(3),
                    cols.get(4), cols.get(5), xrefs);
            acceptOrReject(source, values, line, accepted, rejected, conflicts, seenIds, i + 1);
        }

        if (!headerSeen) {
            rejected.add(new RejectedRecord(source, "", RejectionReason.MALFORMED_SYNTAX,
                    "CSV snapshot contains no header line; expected " + EXPECTED_HEADER));
        }
        return new ParseResult(accepted, rejected, conflicts);
    }

    private static List<String> normalizeHeader(List<String> cols) {
        return cols.stream().map(c -> c.strip().toLowerCase()).toList();
    }

    private static void acceptOrReject(String source, RecordSupport.RawValues values, String rawLine,
                                       List<BioRecord> accepted, List<RejectedRecord> rejected,
                                       List<RejectedRecord> conflicts, Map<String, Integer> seenIds,
                                       int lineNo) {
        RecordSupport.Outcome outcome = RecordSupport.validate(source, values);
        if (outcome instanceof RecordSupport.Outcome.Rejected r) {
            rejected.add(new RejectedRecord(source, rawLine, r.reason(),
                    "line " + lineNo + ": " + r.detail()));
            return;
        }
        BioRecord record = ((RecordSupport.Outcome.Accepted) outcome).record();
        if (seenIds.putIfAbsent(record.localId(), lineNo) != null) {
            conflicts.add(new RejectedRecord(source, rawLine,
                    RejectionReason.DUPLICATE_SOURCE_IDENTIFIER,
                    "line " + lineNo + ": duplicate identifier '" + record.localId()
                            + "' within source '" + source + "'; first occurrence retained deterministically"));
            return;
        }
        accepted.add(record);
    }

    /**
     * Split one CSV line honoring double-quoted fields and escaped quotes ("").
     * Sequences use the amino/nucleotide alphabet and never contain commas, but quoted
     * fields are supported so exports stay standard CSV.
     */
    static List<String> splitCsv(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    field.append(c);
                }
            } else if (c == '"') {
                inQuotes = true;
            } else if (c == ',') {
                out.add(field.toString());
                field.setLength(0);
            } else {
                field.append(c);
            }
        }
        if (inQuotes) {
            throw new IllegalArgumentException("unterminated quoted field");
        }
        out.add(field.toString());
        return out;
    }
}
