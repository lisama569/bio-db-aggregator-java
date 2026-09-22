package com.github.lisama569.biodbaggregatorjava.tests;

import com.github.lisama569.biodbaggregatorjava.model.ImportReport;
import com.github.lisama569.biodbaggregatorjava.model.ResolvedEntity;
import com.github.lisama569.biodbaggregatorjava.resolve.IdentifierResolver;
import com.github.lisama569.biodbaggregatorjava.resolve.ResolutionRules;
import com.github.lisama569.biodbaggregatorjava.service.AggregationService;
import com.github.lisama569.biodbaggregatorjava.service.SnapshotImporter;
import com.github.lisama569.biodbaggregatorjava.snapshot.DelimitedSnapshotParser;
import com.github.lisama569.biodbaggregatorjava.snapshot.JsonlSnapshotParser;
import com.github.lisama569.biodbaggregatorjava.snapshot.SnapshotInput;
import com.github.lisama569.biodbaggregatorjava.snapshot.SourceFormat;
import com.github.lisama569.biodbaggregatorjava.store.SnapshotStore;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 幂等导入、版本切换/回退可复现、并发刷新下的视图一致性测试。
 */
class SnapshotConsistencyTest {

    private static final Logger log = LoggerFactory.getLogger(SnapshotConsistencyTest.class);
    private static final ResolutionRules RULES = new ResolutionRules(
            "rules-v1",
            List.of("uniprot", "refseq", "ensembl", "geneId", "proteinId"),
            List.of("ensembl-snapshots", "uniprot-snapshots", "ncbi-snapshots"));

    private record Ctx(SnapshotImporter importer, SnapshotStore store,
                       AggregationService query) {
    }

    private Ctx newCtx() {
        SnapshotStore store = new SnapshotStore();
        SnapshotImporter importer = new SnapshotImporter(
                List.of(new JsonlSnapshotParser(), new DelimitedSnapshotParser()),
                new IdentifierResolver(), store, RULES);
        return new Ctx(importer, store, new AggregationService(store));
    }

    private List<SnapshotInput> v1Inputs() {
        return List.of(
                SnapshotTestSupport.input("ncbi-snapshots", SourceFormat.CSV, "ncbi-v1.csv"),
                SnapshotTestSupport.input("uniprot-snapshots", SourceFormat.TSV, "uniprot-v1.tsv"),
                SnapshotTestSupport.input("ensembl-snapshots", SourceFormat.JSONL,
                        "ensembl-v1.jsonl"));
    }

    private List<SnapshotInput> v2Inputs() {
        return List.of(
                SnapshotTestSupport.input("ncbi-snapshots", SourceFormat.CSV, "ncbi-v1.csv"),
                SnapshotTestSupport.input("uniprot-snapshots", SourceFormat.TSV, "uniprot-v1.tsv"),
                SnapshotTestSupport.input("ensembl-snapshots", SourceFormat.JSONL,
                        "ensembl-v2.jsonl"));
    }

    @Test
    void repeatedImportIsIdempotentNoCountDrift() {
        Ctx ctx = newCtx();
        ImportReport first = ctx.importer.importSnapshot(v1Inputs());
        ImportReport second = ctx.importer.importSnapshot(v1Inputs());
        ImportReport third = ctx.importer.importSnapshot(v1Inputs());
        log.info("[test:idempotent] v1={} v1Again={} v1Third={} contentHashes=({},{},{})",
                first.version(), second.version(), third.version(),
                first.contentHash(), second.contentHash(), third.contentHash());

        assertEquals(first.version(), second.version());
        assertEquals(first.version(), third.version());
        assertTrue(second.idempotentReuse());
        assertTrue(third.idempotentReuse());
        assertEquals(first.importedCount(), second.importedCount());
        assertEquals(first.rejectedCount(), second.rejectedCount());
        // 只有一个版本，计数无漂移
        assertEquals(1, ctx.store.list().size());
        assertEquals(first.importedCount(),
                ctx.store.current().orElseThrow().records().size());
    }

    @Test
    void rollbackReproducesHistoricalConclusions() {
        Ctx ctx = newCtx();
        ImportReport v1 = ctx.importer.importSnapshot(v1Inputs());
        ImportReport v2 = ctx.importer.importSnapshot(v2Inputs());
        assertNotEquals(v1.version(), v2.version());

        String nameAtV2 = ctx.query
                .resolveByIdentifier("uniprot", "P04637").orElseThrow()
                .attributes().get("name").chosen();
        log.info("[test:rollback] v1={} v2={} proteinName@v2={}",
                v1.version(), v2.version(), nameAtV2);
        assertEquals("p53 isoform a v2", nameAtV2);

        // 回退到 v1，历史结论复现
        assertTrue(ctx.query.rollback(v1.version()));
        String nameAfterRollback = ctx.query
                .resolveByIdentifier("uniprot", "P04637").orElseThrow()
                .attributes().get("name").chosen();
        log.info("[test:rollback] current={} proteinName@afterRollback={}",
                ctx.query.currentVersion().orElseThrow(), nameAfterRollback);
        assertEquals("p53 isoform a", nameAfterRollback);

        // 历史版本视图仍可直接读取且不可变
        List<ResolvedEntity> v2View = ctx.query.listEntitiesAt(v2.version());
        assertEquals(nameAtV2,
                v2View.stream().filter(e -> e.canonicalId().equals("uniprot:P04637"))
                        .findFirst().orElseThrow()
                        .attributes().get("name").chosen());

        // 再导入 v1 内容：幂等复用，且不改变版本总数语义
        int versionCount = ctx.store.list().size();
        ImportReport again = ctx.importer.importSnapshot(v1Inputs());
        assertEquals(v1.version(), again.version());
        assertEquals(versionCount, ctx.store.list().size());
    }

    @Test
    void concurrentQueriesSeeOneConsistentVersionDuringRefresh() throws Exception {
        Ctx ctx = newCtx();
        ctx.importer.importSnapshot(v1Inputs());

        int queryThreads = 8;
        int iterations = 200;
        ExecutorService pool = Executors.newFixedThreadPool(queryThreads + 2);
        CountDownLatch start = new CountDownLatch(1);
        Set<String> observedInconsistencies = ConcurrentHashMap.newKeySet();
        AtomicReference<Throwable> error = new AtomicReference<>();

        // 查询线程：每次查询必须看到版本内自洽的视图——
        // 版本号与该版本实体名必须对应，绝不出现 v1 版本号配 v2 数据的混合
        for (int t = 0; t < queryThreads; t++) {
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < iterations; i++) {
                        // 单次查询入口只钉住一次快照引用：版本号与实体数据
                        // 必须来自同一个不可变快照，杜绝两次读取之间被刷新
                        var pinned = ctx.query.pin();
                        String version = pinned.version();
                        ResolvedEntity protein = ctx.query
                                .resolveOn(pinned, "uniprot", "P04637")
                                .orElseThrow();
                        String name = protein.attributes().get("name").chosen();
                        String expected = snapshotName(version, ctx);
                        if (!expected.equals(name)) {
                            observedInconsistencies.add(
                                    "mixed view: version=" + version
                                            + " name=" + name + " expected=" + expected);
                        }
                    }
                } catch (Throwable th) {
                    error.compareAndSet(null, th);
                }
            });
        }

        // 刷新线程：v1/v2 交替导入与回退
        pool.submit(() -> {
            try {
                start.await();
                for (int i = 0; i < 50; i++) {
                    var r = ctx.importer.importSnapshot(i % 2 == 0 ? v2Inputs() : v1Inputs());
                    log.info("[test:concurrency] refresh {} -> {}", i, r.version());
                }
            } catch (Throwable th) {
                error.compareAndSet(null, th);
            }
        });

        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS), "并发测试超时");
        if (error.get() != null) {
            throw new AssertionError("并发线程异常", error.get());
        }
        log.info("[test:concurrency] 不一致观测数={} 版本列表={}",
                observedInconsistencies.size(),
                ctx.store.list().stream().map(s -> s.version()
                        + "(current=" + s.current() + ")").toList());
        assertTrue(observedInconsistencies.isEmpty(),
                "出现新旧混合视图: " + observedInconsistencies);

        // 刷新线程交替导入相同两份内容，最终仍只有 2 个版本（幂等）
        assertEquals(2, ctx.store.list().size(),
                "重复导入不得产生新版本: " + ctx.store.list());
    }

    /** 通过版本号找到该版本快照里的蛋白名（独立于被测查询路径，直接读历史）。 */
    private String snapshotName(String version, Ctx ctx) {
        return ctx.store.byVersion(version).orElseThrow()
                .entitiesByCanonicalId().get("uniprot:P04637")
                .attributes().get("name").chosen();
    }
}
