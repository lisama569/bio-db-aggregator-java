package com.github.lisama569.biodbaggregatorjava.parser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.lisama569.biodbaggregatorjava.model.BioRecord;
import com.github.lisama569.biodbaggregatorjava.model.RejectedRecord;
import com.github.lisama569.biodbaggregatorjava.model.SourceFormat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parser for JSON snapshot exports. Expected shape: a JSON array of record objects,
 * each with keys id/type/symbol/name/sequence/organism and an "xrefs" object
 * mapping namespace to identifier.
 */
@org.springframework.stereotype.Component
public class JsonSnapshotParser implements SnapshotParser {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public SourceFormat format() {
        return SourceFormat.JSON;
    }

    @Override
    public ParseResult parse(String source, String rawInput) {
        List<BioRecord> accepted = new ArrayList<>();
        List<RejectedRecord> rejected = new ArrayList<>();
        List<RejectedRecord> conflicts = new ArrayList<>();
        Map<String, Integer> seenIds = new LinkedHashMap<>();

        JsonNode root;
        try {
            root = objectMapper.readTree(rawInput);
        } catch (Exception e) {
            rejected.add(new RejectedRecord(source, abbreviate(rawInput),
                    com.github.lisama569.biodbaggregatorjava.model.RejectionReason.MALFORMED_SYNTAX,
                    "payload is not valid JSON: " + e.getClass().getSimpleName()));
            return new ParseResult(accepted, rejected, conflicts);
        }
        if (root == null || !root.isArray()) {
            rejected.add(new RejectedRecord(source, abbreviate(rawInput),
                    com.github.lisama569.biodbaggregatorjava.model.RejectionReason.MALFORMED_SYNTAX,
                    "JSON snapshot root must be an array of record objects"));
            return new ParseResult(accepted, rejected, conflicts);
        }

        int index = 0;
        for (JsonNode node : root) {
            index++;
            String rawLine = node.toString();
                try {
                if (!node.isObject()) {
                    rejected.add(new RejectedRecord(source, rawLine,
                            com.github.lisama569.biodbaggregatorjava.model.RejectionReason.MALFORMED_SYNTAX,
                            "array element #" + index + " is not a JSON object"));
                    continue;
                }
                Map<String, String> xrefs = readXrefs(node, index);
                RecordSupport.RawValues values = new RecordSupport.RawValues(
                        textOrNull(node, "id"),
                        textOrNull(node, "type"),
                        textOrNull(node, "symbol"),
                        textOrNull(node, "name"),
                        textOrNull(node, "sequence"),
                        textOrNull(node, "organism"),
                        xrefs);
                addAfterValidation(source, values, rawLine, accepted, rejected, conflicts, seenIds);
            } catch (XrefShapeException e) {
                rejected.add(new RejectedRecord(source, rawLine,
                        com.github.lisama569.biodbaggregatorjava.model.RejectionReason.MALFORMED_SYNTAX,
                        e.getMessage()));
            }
        }
        return new ParseResult(accepted, rejected, conflicts);
    }

    private static String textOrNull(JsonNode node, String key) {
        JsonNode v = node.get(key);
        if (v == null || v.isNull()) {
            return null;
        }
        if (!v.isTextual()) {
            throw new XrefShapeException("field '" + key + "' must be a JSON string");
        }
        return v.asText();
    }

    private static Map<String, String> readXrefs(JsonNode node, int index) {
        JsonNode x = node.get("xrefs");
        if (x == null || x.isNull()) {
            return Map.of();
        }
        if (!x.isObject()) {
            throw new XrefShapeException("'xrefs' of element #" + index + " must be a JSON object");
        }
        Map<String, String> map = new LinkedHashMap<>();
        x.fields().forEachRemaining(e -> {
            if (!e.getValue().isTextual()) {
                throw new XrefShapeException("xref value for namespace '" + e.getKey()
                        + "' (element #" + index + ") must be a string");
            }
            map.put(e.getKey(), e.getValue().asText());
        });
        return map;
    }

    static void addAfterValidation(String source, RecordSupport.RawValues values,
                                   String rawLine, List<BioRecord> accepted,
                                   List<RejectedRecord> rejected, List<RejectedRecord> conflicts,
                                   Map<String, Integer> seenIds) {
        RecordSupport.Outcome outcome = RecordSupport.validate(source, values);
        if (outcome instanceof RecordSupport.Outcome.Rejected r) {
            rejected.add(new RejectedRecord(source, rawLine, r.reason(), r.detail()));
            return;
        }
        BioRecord record = ((RecordSupport.Outcome.Accepted) outcome).record();
        Integer firstLine = seenIds.putIfAbsent(record.localId(), 1);
        if (firstLine != null) {
            conflicts.add(new RejectedRecord(source, rawLine,
                    com.github.lisama569.biodbaggregatorjava.model.RejectionReason.DUPLICATE_SOURCE_IDENTIFIER,
                    "duplicate identifier '" + record.localId()
                            + "' within source '" + source + "'; first occurrence retained deterministically"));
            return;
        }
        accepted.add(record);
    }

    private static String abbreviate(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.strip();
        return s.length() <= 200 ? s : s.substring(0, 200) + "...";
    }

    private static final class XrefShapeException extends RuntimeException {
        XrefShapeException(String message) {
            super(message);
        }
    }
}
