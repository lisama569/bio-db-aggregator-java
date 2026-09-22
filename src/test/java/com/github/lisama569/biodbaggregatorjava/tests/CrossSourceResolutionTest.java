package com.github.lisama569.biodbaggregatorjava.tests;

import com.github.lisama569.biodbaggregatorjava.model.EntityType;
import com.github.lisama569.biodbaggregatorjava.model.ResolutionStatus;
import com.github.lisama569.biodbaggregatorjava.model.ResolvedEntity;
import com.github.lisama569.biodbaggregatorjava.resolve.IdentifierResolver;
import com.github.lisama569.biodbaggregatorjava.resolve.ResolutionResult;
import com.github.lisama569.biodbaggregatorjava.resolve.ResolutionRules;
import com.github.lisama569.biodbaggregatorjava.service.SnapshotImporter;
import com.github.lisama569.biodbaggregatorjava.snapshot.DelimitedSnapshotParser;
import com.github.lisama569.biodbaggregatorjava.snapshot.JsonlSnapshotParser;
import com.github.lisama569.biodbaggregatorjava.snapshot.Snapshot;
import com.github.lisama569.biodbaggregatorjava.snapshot.SnapshotInput;
import com.github.lisama569.biodbaggregatorjava.snapshot.SourceFormat;
import com.github.lisama569.biodbaggregatorjava.store.SnapshotStore;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 跨源标识符解析测试：优先级裁决、确定性平局冲突、来源内主键冲突。
 */
class CrossSourceResolutionTest {

    private static final Logger log = LoggerFactory.getLogger(CrossSourceResolutionTest.class);
    private static final ResolutionRules RULES = new ResolutionRules(
            "rules-v1",
            List.of("uniprot", "refseq", "ensembl", "geneId", "proteinId"),
            List.of("ensembl-snapshots", "uniprot-snapshots", "ncbi-snapshots"));

    /** 测试上下文：同一组 importer/store。 */
    private record Ctx(SnapshotImporter importer, SnapshotStore store) {
        Snapshot snapshot() {
            return store.current().orElseThrow();
        }

        com.github.lisama569.biodbaggregatorjava.model.ImportReport doImport(
                SnapshotInput... inputs) {
            return importer.importSnapshot(List.of(inputs));
        }
    }

    private Ctx newCtx() {
        SnapshotStore store = new SnapshotStore();
        SnapshotImporter importer = new SnapshotImporter(
                List.of(new JsonlSnapshotParser(), new DelimitedSnapshotParser()),
                new IdentifierResolver(), store, RULES);
        return new Ctx(importer, store);
    }

    private SnapshotInput src(String source, SourceFormat fmt, String resource) {
        return SnapshotTestSupport.input(source, fmt, resource);
    }

    @Test
    void priorityWinsAndCanonicalIdFollowsNamespacePriority() {
        Ctx ctx = newCtx();
        var report = ctx.doImport(
                src("ncbi-snapshots", SourceFormat.CSV, "ncbi-v1.csv"),
                src("uniprot-snapshots", SourceFormat.TSV, "uniprot-v1.tsv"),
                src("ensembl-snapshots", SourceFormat.JSONL, "ensembl-v1.jsonl"));
        log.info("[test:resolution] snapshotVersion={} rulesVersion={} imported={} rejected={}",
                report.version(), RULES.rulesVersion(), report.importedCount(),
                report.rejectedCount());

        Snapshot snapshot = ctx.snapshot();
        ResolvedEntity protein = snapshot.entitiesByCanonicalId().get("uniprot:P04637");
        assertNotNull(protein, "canonicalId 应取最高优先级命名空间 uniprot");
        assertEquals(EntityType.PROTEIN, protein.type());
        log.info("[test:resolution] protein canonicalId={} status={} nameBasis={}",
                protein.canonicalId(), protein.status(),
                protein.attributes().get("name").basis());
        // ensembl 来源优先级高于 uniprot/ncbi -> name 取 ensembl 值
        assertEquals("p53 isoform a", protein.attributes().get("name").chosen());
        assertEquals(ResolutionStatus.RESOLVED, protein.status());
        assertTrue(protein.identifiers().containsKey("uniprot"));
        assertTrue(protein.identifiers().containsKey("refseq"));
        assertTrue(protein.identifiers().containsKey("ensembl"));

        // gene 簇通过 geneId:7157 连通
        String canonical = snapshot.canonicalIdByIdentifier().get("geneId:7157");
        assertNotNull(canonical);
        ResolvedEntity gene = snapshot.entitiesByCanonicalId().get(canonical);
        assertEquals(EntityType.GENE, gene.type());
        assertEquals("tumor protein p53 (ENSG)",
                gene.attributes().get("name").chosen());
        log.info("[test:resolution] gene canonicalId={} nameBasis={}",
                gene.canonicalId(), gene.attributes().get("name").basis());
    }

    @Test
    void unrankedSourcesCannotBeatRankedSource() {
        Ctx ctx = newCtx();
        ctx.doImport(
                src("uniprot-snapshots", SourceFormat.TSV, "uniprot-v1.tsv"),
                src("labA", SourceFormat.CSV, "labA-v1.csv"),
                src("labB", SourceFormat.CSV, "labB-v1.csv"));
        ResolvedEntity protein = ctx.snapshot()
                .entitiesByCanonicalId().get("uniprot:P04637");
        assertNotNull(protein);
        // 表内来源 uniprot-snapshots 严格优先于表外 labA/labB
        assertEquals("p53 tumor suppressor",
                protein.attributes().get("name").chosen());
    }

    @Test
    void equalPrioritySourcesProduceDeterministicConflictNotSilentPick() {
        Ctx ctx = newCtx();
        var report = ctx.doImport(
                src("labA", SourceFormat.CSV, "labA-v1.csv"),
                src("labB", SourceFormat.CSV, "labB-v1.csv"));
        log.info("[test:tie-conflict] snapshotVersion={} imported={}",
                report.version(), report.importedCount());

        ResolvedEntity tied = ctx.snapshot()
                .entitiesByCanonicalId().get("uniprot:P04637");
        assertNotNull(tied);
        log.info("[test:tie-conflict] status={} chosen={} basis={} candidates={}",
                tied.status(), tied.attributes().get("name").chosen(),
                tied.attributes().get("name").basis(),
                tied.attributes().get("name").candidates().stream()
                        .map(c -> c.source() + "=" + c.value()).toList());
        assertEquals(ResolutionStatus.CONFLICT, tied.status());
        // rank 相同、ordinal 相同 -> 按值字典序确定性选中，且保留全部候选
        assertEquals("lab-alpha-name", tied.attributes().get("name").chosen());
        assertTrue(tied.attributes().get("name").tieBreak());
        assertEquals(2, tied.attributes().get("name").candidates().size());

        // 重新导入一遍结论必须完全一致（可复现）
        Ctx ctx2 = newCtx();
        ctx2.doImport(
                src("labA", SourceFormat.CSV, "labA-v1.csv"),
                src("labB", SourceFormat.CSV, "labB-v1.csv"));
        ResolvedEntity tied2 = ctx2.snapshot()
                .entitiesByCanonicalId().get("uniprot:P04637");
        assertEquals(tied.attributes().get("name").chosen(),
                tied2.attributes().get("name").chosen());
        assertEquals(tied.ambiguityNote(), tied2.ambiguityNote());
    }

    @Test
    void withinSourceDuplicateConflictingIdsAreAmbiguous() {
        String jsonl = """
                {"id":"DUP1","type":"protein","uniprot":"P00001","symbol":"AAA","name":"first copy"}
                {"id":"DUP1","type":"protein","uniprot":"P00001","symbol":"BBB","name":"second copy differs"}
                """;
        var parsed = new JsonlSnapshotParser()
                .parse(new SnapshotInput("srcX", SourceFormat.JSONL, jsonl));
        ResolutionResult result = new IdentifierResolver().resolve(parsed.accepted(), RULES);
        log.info("[test:within-conflict] input=\n{}", jsonl);
        result.entitiesByCanonicalId().values().forEach(e -> log.info(
                "[test:within-conflict] canonicalId={} status={} note={}",
                e.canonicalId(), e.status(), e.ambiguityNote()));
        ResolvedEntity e = result.entitiesByCanonicalId().values().stream()
                .filter(x -> x.status() == ResolutionStatus.AMBIGUOUS)
                .findFirst().orElseThrow();
        assertEquals(1, result.ambiguousCount());
        assertTrue(e.ambiguityNote().contains("DUP1"));
        // 歧义状态优先于一切；note 必须列出全部冲突行，不静默丢弃
        assertTrue(e.ambiguityNote().contains("[1, 2]"),
                "歧义说明应包含冲突行号: " + e.ambiguityNote());
    }

    @Test
    void withinSourceConflictIsRejectedWithDistinctReasonAtImport() {
        String jsonl = """
                {"id":"DUP9","type":"protein","uniprot":"P00009","symbol":"AAA","name":"copy A"}
                {"id":"DUP9","type":"protein","uniprot":"P00009","symbol":"ZZZ","name":"copy B"}
                """;
        Ctx ctx = newCtx();
        var report = ctx.importer.importSnapshot(List.of(
                new SnapshotInput("srcZ", SourceFormat.JSONL, jsonl)));
        log.info("[test:within-conflict-import] version={} rejectedReasons={}",
                report.version(),
                report.rejected().stream().map(r -> r.reason().name()).toList());
        var reasons = report.rejected().stream()
                .map(r -> r.reason()).collect(java.util.stream.Collectors.toSet());
        assertTrue(reasons.contains(
                        com.github.lisama569.biodbaggregatorjava.model.RejectReason
                                .WITHIN_SOURCE_ID_CONFLICT),
                "来源内主键冲突必须以独立原因呈现");
        ResolvedEntity e = ctx.store().current().orElseThrow()
                .entitiesByCanonicalId().get("uniprot:P00009");
        assertNotNull(e);
        assertEquals(ResolutionStatus.AMBIGUOUS, e.status());
    }

    @Test
    void identicalDuplicatesAreDedupedNotAmbiguous() {
        String jsonl = """
                {"id":"SAME1","type":"protein","uniprot":"P00002","symbol":"CCC","name":"same"}
                {"id":"SAME1","type":"protein","uniprot":"P00002","symbol":"CCC","name":"same"}
                """;
        var parsed = new JsonlSnapshotParser()
                .parse(new SnapshotInput("srcY", SourceFormat.JSONL, jsonl));
        ResolutionResult result = new IdentifierResolver().resolve(parsed.accepted(), RULES);
        assertEquals(1, result.entitiesByCanonicalId().size());
        assertEquals(0, result.ambiguousCount());
    }
}
