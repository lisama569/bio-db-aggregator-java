package com.github.lisama569.biodbaggregatorjava.parser;

import com.github.lisama569.biodbaggregatorjava.model.BioRecord;
import com.github.lisama569.biodbaggregatorjava.model.EntityType;
import com.github.lisama569.biodbaggregatorjava.model.RejectionReason;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Shared normalization and validation rules for all snapshot formats. */
final class RecordSupport {

    /** Local identifiers are restricted to a stable, comparable character set. */
    private static final Pattern VALID_ID = Pattern.compile("[A-Za-z0-9._-]+");
    private static final Pattern VALID_NS = Pattern.compile("[A-Za-z0-9._-]+");

    private RecordSupport() {
    }

    /** Field values extracted from any format before normalization. */
    record RawValues(String localId, String type, String symbol, String name,
                     String sequence, String organism, Map<String, String> xrefs) {
    }

    /** Result of validating one raw record: either a normalized record or a distinguishable rejection. */
    sealed interface Outcome permits Outcome.Accepted, Outcome.Rejected {
        record Accepted(BioRecord record) implements Outcome {
        }

        record Rejected(RejectionReason reason, String detail) implements Outcome {
        }
    }

    static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    /**
     * Validate in a fixed order so the rejection reason is deterministic and distinguishable:
     * missing id -> missing type -> unknown type -> invalid id syntax -> missing symbol.
     */
    static Outcome validate(String source, RawValues v) {
        String id = trimToNull(v.localId());
        String typeRaw = trimToNull(v.type());
        String symbol = trimToNull(v.symbol());

        if (id == null) {
            return new Outcome.Rejected(RejectionReason.MISSING_REQUIRED_FIELD,
                    "required field 'id' is missing or blank");
        }
        if (typeRaw == null) {
            return new Outcome.Rejected(RejectionReason.MISSING_REQUIRED_FIELD,
                    "required field 'type' is missing or blank (id=" + id + ")");
        }
        EntityType type;
        try {
            type = EntityType.valueOf(typeRaw.toUpperCase());
        } catch (IllegalArgumentException e) {
            return new Outcome.Rejected(RejectionReason.UNKNOWN_ENTITY_TYPE,
                    "unknown entity type '" + typeRaw + "' (id=" + id + ")");
        }
        if (!VALID_ID.matcher(id).matches()) {
            return new Outcome.Rejected(RejectionReason.INVALID_IDENTIFIER,
                    "identifier '" + id + "' contains illegal characters; allowed: [A-Za-z0-9._-]");
        }
        if (symbol == null) {
            return new Outcome.Rejected(RejectionReason.MISSING_REQUIRED_FIELD,
                    "required field 'symbol' is missing or blank (id=" + id + ")");
        }

        Map<String, String> xrefs = v.xrefs() == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(v.xrefs()));
        BioRecord record = new BioRecord(
                source + "#" + id,
                source,
                type,
                id,
                symbol,
                trimToNull(v.name()),
                emptyToNull(v.sequence()),
                trimToNull(v.organism()),
                xrefs);
        return new Outcome.Accepted(record);
    }

    private static String emptyToNull(String s) {
        String t = trimToNull(s);
        // whitespace-only sequence is treated as absent, not as data
        return t;
    }

    /**
     * Parse a semicolon-separated namespace:value list (CSV/FLAT xref column).
     *
     * @throws InvalidXrefException when one pair is not syntactically namespace:value
     */
    static Map<String, String> parseXrefList(String raw) {
        String body = trimToNull(raw);
        if (body == null) {
            return Map.of();
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (String pair : body.split(";")) {
            String p = pair.trim();
            if (p.isEmpty()) {
                continue;
            }
            int idx = p.indexOf(':');
            if (idx <= 0 || idx == p.length() - 1) {
                throw new InvalidXrefException("malformed cross-reference '" + p
                        + "', expected namespace:value");
            }
            String ns = p.substring(0, idx).trim();
            String val = p.substring(idx + 1).trim();
            if (!VALID_NS.matcher(ns).matches() || !VALID_ID.matcher(val).matches()) {
                throw new InvalidXrefException("malformed cross-reference '" + p + "'");
            }
            out.put(ns, val);
        }
        return Collections.unmodifiableMap(out);
    }

    /** Signals an unparseable cross-reference pair; callers map it to MALFORMED_SYNTAX. */
    static final class InvalidXrefException extends RuntimeException {
        InvalidXrefException(String message) {
            super(message);
        }
    }
}
