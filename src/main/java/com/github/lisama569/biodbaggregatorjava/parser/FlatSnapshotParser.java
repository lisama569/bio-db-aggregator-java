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
 * Parser for pipe-delimited ("FLAT") snapshot exports:
 * <pre>REC|id|type|symbol|name|sequence|organism|ns:val;ns:val</pre>
 * Lines beginning with '#' are comments and blank lines are ignored. Every data line
 * must start with the literal {@code REC} marker and carry exactly 8 fields.
 */
@org.springframework.stereotype.Component
public class FlatSnapshotParser implements SnapshotParser {

    private static final String MARKER = "REC";
    private static final int FIELD_COUNT = 8;

    @Override
    public SourceFormat format() {
        return SourceFormat.FLAT;
    }

    @Override
    public ParseResult parse(String source, String rawInput) {
        List<BioRecord> accepted = new ArrayList<>();
        List<RejectedRecord> rejected = new ArrayList<>();
        List<RejectedRecord> conflicts = new ArrayList<>();
        Map<String, Integer> seenIds = new LinkedHashMap<>();

        String[] lines = rawInput.split("\\R", -1);
        int dataCount = 0;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.isBlank() || line.stripLeading().startsWith("#")) {
                continue;
            }
            dataCount++;
            String[] parts = line.split("\\|", -1);
            if (parts.length != FIELD_COUNT || !MARKER.equals(parts[0].strip())) {
                rejected.add(new RejectedRecord(source, line, RejectionReason.MALFORMED_SYNTAX,
                        "line " + (i + 1) + ": expected '" + MARKER
                                + "|id|type|symbol|name|sequence|organism|xrefs' ("
                                + FIELD_COUNT + " fields) but got " + parts.length + " fields"
                                + (parts.length > 0 ? " with marker '" + parts[0] + "'" : "")));
                continue;
            }
            Map<String, String> xrefs;
            try {
                xrefs = RecordSupport.parseXrefList(parts[7]);
            } catch (RecordSupport.InvalidXrefException e) {
                rejected.add(new RejectedRecord(source, line, RejectionReason.MALFORMED_SYNTAX,
                        "line " + (i + 1) + ": " + e.getMessage()));
                continue;
            }
            RecordSupport.RawValues values = new RecordSupport.RawValues(
                    parts[1], parts[2], parts[3], parts[4], parts[5], parts[6], xrefs);
            RecordSupport.Outcome outcome = RecordSupport.validate(source, values);
            if (outcome instanceof RecordSupport.Outcome.Rejected r) {
                rejected.add(new RejectedRecord(source, line, r.reason(),
                        "line " + (i + 1) + ": " + r.detail()));
                continue;
            }
            BioRecord record = ((RecordSupport.Outcome.Accepted) outcome).record();
            if (seenIds.putIfAbsent(record.localId(), i + 1) != null) {
                conflicts.add(new RejectedRecord(source, line,
                        RejectionReason.DUPLICATE_SOURCE_IDENTIFIER,
                        "line " + (i + 1) + ": duplicate identifier '" + record.localId()
                                + "' within source '" + source + "'; first occurrence retained deterministically"));
                continue;
            }
            accepted.add(record);
        }

        if (dataCount == 0) {
            rejected.add(new RejectedRecord(source, "", RejectionReason.MALFORMED_SYNTAX,
                    "FLAT snapshot contains no data records"));
        }
        return new ParseResult(accepted, rejected, conflicts);
    }
}
