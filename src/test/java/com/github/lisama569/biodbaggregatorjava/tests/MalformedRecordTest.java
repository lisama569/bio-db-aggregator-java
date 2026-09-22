package com.github.lisama569.biodbaggregatorjava.tests;

import com.github.lisama569.biodbaggregatorjava.model.RejectReason;
import com.github.lisama569.biodbaggregatorjava.model.RejectedRecord;
import com.github.lisama569.biodbaggregatorjava.snapshot.DelimitedSnapshotParser;
import com.github.lisama569.biodbaggregatorjava.snapshot.JsonlSnapshotParser;
import com.github.lisama569.biodbaggregatorjava.snapshot.SnapshotInput;
import com.github.lisama569.biodbaggregatorjava.snapshot.SourceFormat;
import com.github.lisama569.biodbaggregatorjava.snapshot.SourceParseResult;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 畸形/缺失/结构错误记录的归类测试：原因必须可区分，且记录不被静默丢弃。
 */
class MalformedRecordTest {

    private static final Logger log = LoggerFactory.getLogger(MalformedRecordTest.class);

    @Test
    void jsonlClassifiesBadRowsWithDistinctReasons() {
        SnapshotInput in = new SnapshotInput("ensembl-snapshots", SourceFormat.JSONL,
                SnapshotTestSupport.resource("ensembl-v1.jsonl"));
        SourceParseResult result = new JsonlSnapshotParser().parse(in);

        Map<Integer, RejectedRecord> byLine = result.rejected().stream()
                .collect(Collectors.toMap(RejectedRecord::lineNumber, r -> r));
        log.info("[test:malformed-jsonl] input=\n{}", in.content());
        result.rejected().forEach(r -> log.info(
                "[test:malformed-jsonl] line={} reason={} detail={} raw={}",
                r.lineNumber(), r.reason(), r.detail(), r.rawLine()));

        // 第 3 行 JSON 截断 -> MALFORMED_RECORD
        RejectedRecord badJson = byLine.get(3);
        assertNotNull(badJson);
        assertEquals(RejectReason.MALFORMED_RECORD, badJson.reason());

        // 第 4 行 type=virus -> MISSING_REQUIRED_FIELD（未知类型）
        RejectedRecord badType = byLine.get(4);
        assertNotNull(badType);
        assertEquals(RejectReason.MISSING_REQUIRED_FIELD, badType.reason());
        assertTrue(badType.detail().contains("gene/protein"));

        // 第 5 行没有任何标识符 -> MISSING_REQUIRED_FIELD
        RejectedRecord noIds = byLine.get(5);
        assertNotNull(noIds);
        assertEquals(RejectReason.MISSING_REQUIRED_FIELD, noIds.reason());

        // 前两行正常接受，没有被坏记录连累
        assertEquals(2, result.accepted().size());
    }

    @Test
    void csvAndTsvClassifyStructuralAndSemanticErrors() {
        SnapshotInput csv = new SnapshotInput("ncbi-snapshots", SourceFormat.CSV,
                SnapshotTestSupport.resource("ncbi-v1.csv"));
        SourceParseResult csvResult = new DelimitedSnapshotParser().parse(csv);
        log.info("[test:malformed-csv] input=\n{}", csv.content());
        csvResult.rejected().forEach(r -> log.info(
                "[test:malformed-csv] line={} reason={} detail={} raw={}",
                r.lineNumber(), r.reason(), r.detail(), r.rawLine()));

        Map<Integer, RejectedRecord> byLine = csvResult.rejected().stream()
                .collect(Collectors.toMap(RejectedRecord::lineNumber, r -> r));
        // 第 4 行：无任何跨源标识符 -> MISSING_REQUIRED_FIELD
        assertEquals(RejectReason.MISSING_REQUIRED_FIELD, byLine.get(4).reason());
        // 第 5 行：主键为空 -> MISSING_REQUIRED_FIELD
        assertEquals(RejectReason.MISSING_REQUIRED_FIELD, byLine.get(5).reason());
        // 第 6 行：列数与表头不一致 -> MALFORMED_RECORD
        assertEquals(RejectReason.MALFORMED_RECORD, byLine.get(6).reason());
        assertEquals(2, csvResult.accepted().size());

        SnapshotInput tsv = new SnapshotInput("uniprot-snapshots", SourceFormat.TSV,
                SnapshotTestSupport.resource("uniprot-v1.tsv"));
        SourceParseResult tsvResult = new DelimitedSnapshotParser().parse(tsv);
        log.info("[test:malformed-tsv] input=\n{}", tsv.content());
        tsvResult.rejected().forEach(r -> log.info(
                "[test:malformed-tsv] line={} reason={} detail={} raw={}",
                r.lineNumber(), r.reason(), r.detail(), r.rawLine()));
        assertEquals(1, tsvResult.rejected().size());
        assertEquals(RejectReason.MISSING_REQUIRED_FIELD,
                tsvResult.rejected().get(0).reason());
        assertEquals(2, tsvResult.accepted().size());
    }

    @Test
    void quotedFieldsAndEmbeddedNewlinesAreParsed() {
        String csv = "id,type,geneId,symbol,name\n"
                + "G1,gene,7157,TP53,\"has, comma\"\n"
                + "G2,gene,7158,\"X\"\"Y\",\"line1\nline2\"\n";
        SourceParseResult result = new DelimitedSnapshotParser()
                .parse(new SnapshotInput("ncbi-snapshots", SourceFormat.CSV, csv));
        log.info("[test:csv-quotes] input={}\naccepted={} rejected={}",
                csv, result.accepted().size(), result.rejected());
        assertEquals(0, result.rejected().size());
        assertEquals(2, result.accepted().size());
        assertEquals("has, comma", result.accepted().get(0).name());
        assertEquals("X\"Y", result.accepted().get(1).symbol());
        assertTrue(result.accepted().get(1).name().contains("line1\nline2"));
    }
}
