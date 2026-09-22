package com.github.lisama569.biodbaggregatorjava.tests;

import com.github.lisama569.biodbaggregatorjava.model.RejectedRecord;
import com.github.lisama569.biodbaggregatorjava.model.RejectionReason;
import com.github.lisama569.biodbaggregatorjava.model.SourceFormat;
import com.github.lisama569.biodbaggregatorjava.parser.CsvSnapshotParser;
import com.github.lisama569.biodbaggregatorjava.parser.FlatSnapshotParser;
import com.github.lisama569.biodbaggregatorjava.parser.JsonSnapshotParser;
import com.github.lisama569.biodbaggregatorjava.parser.SnapshotParser;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Malformed / missing-field / within-source duplicate classification tests.
 * Each test logs the raw input records and the distinguishable decision reason.
 */
class SnapshotParserTest {

    private static final Logger log = LoggerFactory.getLogger(SnapshotParserTest.class);

    private final Map<SourceFormat, SnapshotParser> parsers = Map.of(
            SourceFormat.JSON, new JsonSnapshotParser(),
            SourceFormat.CSV, new CsvSnapshotParser(),
            SourceFormat.FLAT, new FlatSnapshotParser());

    private String resource(String name) throws Exception {
        try (var in = getClass().getResourceAsStream("/snapshots/" + name)) {
            assertTrue(in != null, "missing test resource " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private Map<RejectionReason, Long> countByReason(List<RejectedRecord> records) {
        return records.stream().collect(Collectors.groupingBy(RejectedRecord::reason, Collectors.counting()));
    }

    private void logResult(String caseName, String input, SnapshotParser.ParseResult result) {
        log.info("[{}] INPUT ({} bytes):\n{}", caseName, input.length(), input.strip());
        result.accepted().forEach(r -> log.info("[{}] ACCEPTED uid={} type={} symbol={} xrefs={}",
                caseName, r.recordUid(), r.type(), r.symbol(), r.crossReferences()));
        result.rejected().forEach(r -> log.info("[{}] REJECTED reason={} detail={} raw={}",
                caseName, r.reason(), r.detail(), abbreviate(r.rawLine())));
        result.conflicts().forEach(r -> log.info("[{}] CONFLICT reason={} detail={} raw={}",
                caseName, r.reason(), r.detail(), abbreviate(r.rawLine())));
    }

    private static String abbreviate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= 120 ? s : s.substring(0, 120) + "...";
    }

    @Test
    void jsonRejectionsAreDistinguishable() throws Exception {
        String raw = resource("v1_refseq.json");
        var result = parsers.get(SourceFormat.JSON).parse("refseq", raw);
        logResult("json/v1_refseq", raw, result);

        assertEquals(3, result.accepted().size(), "three well-formed records accepted");
        Map<RejectionReason, Long> reasons = countByReason(result.rejected());
        // blank id => MISSING_REQUIRED_FIELD; "bad id!" => INVALID_IDENTIFIER
        assertEquals(1L, reasons.get(RejectionReason.MISSING_REQUIRED_FIELD));
        assertEquals(1L, reasons.get(RejectionReason.INVALID_IDENTIFIER));
        assertEquals(1, result.conflicts().size());
        assertEquals(RejectionReason.DUPLICATE_SOURCE_IDENTIFIER, result.conflicts().get(0).reason());
        assertTrue(result.conflicts().get(0).detail().contains("NM_000518"));
    }

    @Test
    void jsonMalformedPayloadsAreClassifiedAsSyntaxErrors() throws Exception {
        for (String file : List.of("malformed_payload.json", "malformed_payload_broken.json")) {
            String raw = resource(file);
            var result = parsers.get(SourceFormat.JSON).parse("refseq", raw);
            logResult("json/" + file, raw, result);
            assertTrue(result.accepted().isEmpty());
            assertEquals(1, result.rejected().size());
            assertEquals(RejectionReason.MALFORMED_SYNTAX, result.rejected().get(0).reason());
        }
    }

    @Test
    void csvRejectionsAreDistinguishable() throws Exception {
        String raw = resource("v1_uniprot.csv");
        var result = parsers.get(SourceFormat.CSV).parse("uniprot", raw);
        logResult("csv/v1_uniprot", raw, result);

        assertEquals(4, result.accepted().size(), "4 well-formed data rows");
        Map<RejectionReason, Long> reasons = countByReason(result.rejected());
        // rows: blank id (missing), unknown widget type, too-few-columns (syntax)
        assertEquals(1L, reasons.get(RejectionReason.MISSING_REQUIRED_FIELD));
        assertEquals(1L, reasons.get(RejectionReason.UNKNOWN_ENTITY_TYPE));
        assertEquals(1L, reasons.get(RejectionReason.MALFORMED_SYNTAX));
        assertEquals(1, result.conflicts().size());
        assertEquals(RejectionReason.DUPLICATE_SOURCE_IDENTIFIER, result.conflicts().get(0).reason());
        // malformed details carry line numbers so causes are distinguishable
        assertTrue(result.rejected().stream()
                .anyMatch(r -> r.detail().contains("line") && r.detail().contains("columns")));
    }

    @Test
    void csvBadHeaderAndUnterminatedQuoteAreSyntaxErrors() throws Exception {
        for (String file : List.of("malformed_header.csv", "malformed_quote.csv")) {
            String raw = resource(file);
            var result = parsers.get(SourceFormat.CSV).parse("uniprot", raw);
            logResult("csv/" + file, raw, result);
            assertFalse(result.rejected().isEmpty());
            assertEquals(RejectionReason.MALFORMED_SYNTAX, result.rejected().get(0).reason());
        }
    }

    @Test
    void flatRejectionsAreDistinguishable() throws Exception {
        String raw = resource("v1_ensembl.flat");
        var result = parsers.get(SourceFormat.FLAT).parse("ensembl", raw);
        logResult("flat/v1_ensembl", raw, result);

        assertEquals(3, result.accepted().size(), "2 valid genes/proteins + 1 dangling-ref protein");
        Map<RejectionReason, Long> reasons = countByReason(result.rejected());
        // BAD marker line -> syntax; NOSYMBOL -> missing field
        assertEquals(1L, reasons.get(RejectionReason.MALFORMED_SYNTAX));
        assertEquals(1L, reasons.get(RejectionReason.MISSING_REQUIRED_FIELD));
        assertEquals(1, result.conflicts().size());
        assertEquals(RejectionReason.DUPLICATE_SOURCE_IDENTIFIER, result.conflicts().get(0).reason());
    }

    @Test
    void flatRejectsEmptyAndMarkerOnlyInput() {
        String raw = "   \n# only a comment\n";
        var result = parsers.get(SourceFormat.FLAT).parse("ensembl", raw);
        logResult("flat/empty", raw, result);
        assertEquals(1, result.rejected().size());
        assertEquals(RejectionReason.MALFORMED_SYNTAX, result.rejected().get(0).reason());
    }
}
