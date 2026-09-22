package com.github.lisama569.biodbaggregatorjava.tests;

import com.github.lisama569.biodbaggregatorjava.model.ImportReport;
import com.github.lisama569.biodbaggregatorjava.model.ResolutionReport;
import com.github.lisama569.biodbaggregatorjava.model.ResolvedEntity;
import com.github.lisama569.biodbaggregatorjava.model.SourceFormat;
import com.github.lisama569.biodbaggregatorjava.service.AggregatorService;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end import / idempotency / version switching / cross-source aggregation tests
 * driven by the local snapshot resource files.
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class SnapshotServiceIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(SnapshotServiceIntegrationTest.class);

    @Autowired
    private AggregatorService service;

    private String resource(String name) throws Exception {
        try (var in = getClass().getResourceAsStream("/snapshots/" + name)) {
            assertNotNull(in, "missing resource " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private ImportReport importV1(String source, SourceFormat format, String file) throws Exception {
        String raw = resource(file);
        log.info(">>> import v1 source={} format={} bytes={}\n{}", source, format, raw.length(), raw.strip());
        ImportReport report = service.importSnapshot("v1", source, format, raw);
        log.info("<<< import report: version={} source={} status={} accepted={} rejected={} conflicts={}",
                report.snapshotVersion(), report.source(), report.status(),
                report.acceptedCount(), report.rejectedCount(), report.conflictCount());
        return report;
    }

    @Test
    void multiSourceImportAggregationAndIdempotentReplay() throws Exception {
        ImportReport refseq = importV1("refseq", SourceFormat.JSON, "v1_refseq.json");
        ImportReport uniprot = importV1("uniprot", SourceFormat.CSV, "v1_uniprot.csv");
        ImportReport ensembl = importV1("ensembl", SourceFormat.FLAT, "v1_ensembl.flat");

        assertEquals("CREATED", refseq.status());
        assertEquals(3, refseq.acceptedCount());
        assertEquals("CREATED", uniprot.status());
        assertEquals(4, uniprot.acceptedCount());
        assertEquals("CREATED", ensembl.status());
        assertEquals(3, ensembl.acceptedCount());

        // ---- idempotent replay: same content -> DUPLICATE, no new records, no drift ----
        ImportReport replay = importV1("refseq", SourceFormat.JSON, "v1_refseq.json");
        assertEquals("DUPLICATE", replay.status());
        assertTrue(replay.duplicateImport());
        assertEquals(3, replay.acceptedCount(), "replay reports the same parsed counts");

        var snapshot = service.getSnapshot("v1").orElseThrow();
        assertEquals(10, snapshot.records().size(),
                "3+4+3 records stored once; replay must not duplicate");

        ResolutionReport resolution = service.getResolution("v1").orElseThrow();
        log.info("v1 resolution: rulesVersion={} entities={} fieldConflicts={} ambiguities={}",
                resolution.rulesVersion(), resolution.entities().size(),
                resolution.conflictCount(), resolution.ambiguities().size());
        // 10 records: HBB gene cluster (3) + HBB protein cluster (3) + HBA2 gene (1)
        // + HBA protein uniprot (1) + dangling ensembl protein (1)
        assertEquals(List.of("v1"), service.versions());
        assertEquals("v1", service.currentVersion().orElseThrow());

        // priority: refseq wins name for the merged HBB gene; conflict is recorded
        ResolvedEntity hbbGene = service.resolveIdentifier("NM_000518").orElseThrow();
        assertEquals("GENE:refseq:NM_000518", hbbGene.entityKey());
        assertEquals("hemoglobin subunit beta", hbbGene.canonicalName());
        assertTrue(hbbGene.fieldConflicts().stream()
                        .anyMatch(c -> c.field().equals("name") && c.winnerSource().equals("refseq")),
                "name disagreement must be retained as a conflict, winner = refseq");

        // identifier resolution works via local id, namespaced id and entity key
        assertEquals(hbbGene.entityKey(),
                service.resolveIdentifier("refseq:NM_000518").orElseThrow().entityKey());
        assertEquals(hbbGene.entityKey(),
                service.resolveIdentifier("GENE:refseq:NM_000518").orElseThrow().entityKey());
        assertEquals(hbbGene.entityKey(),
                service.resolveIdentifier("ENSG00000244734").orElseThrow().entityKey());

        // dangling xref kept, not silently dropped
        assertTrue(resolution.ambiguities().stream().anyMatch(a ->
                a.token().equals("uniprot:P00000")
                        && a.reason().equals("DANGLING_REFERENCE")));
    }

    @Test
    void conflictingReimportIsRejectedNotSilentlyOverwritten() throws Exception {
        String first = "[{\"id\":\"N1\",\"type\":\"gene\",\"symbol\":\"G1\",\"name\":\"first\"}]";
        String changed = "[{\"id\":\"N1\",\"type\":\"gene\",\"symbol\":\"G1\",\"name\":\"changed content\"}]";
        log.info(">>> import first: {}", first);
        assertEquals("CREATED",
                service.importSnapshot("vr", "refseq", SourceFormat.JSON, first).status());
        log.info(">>> re-import DIFFERENT content: {}", changed);
        ImportReport conflict = service.importSnapshot("vr", "refseq", SourceFormat.JSON, changed);
        log.info("<<< re-import status={}", conflict.status());
        assertEquals("CONFLICTING_REIMPORT", conflict.status());
        assertFalse(conflict.duplicateImport());
        // original value still authoritative
        assertEquals("first",
                service.resolveIdentifier("N1").orElseThrow().canonicalName());
    }

    @Test
    void versionSwitchAndRollbackShowDistinctCompleteViews() throws Exception {
        service.importSnapshot("hist-v1", "refseq", SourceFormat.JSON,
                "[{\"id\":\"N1\",\"type\":\"gene\",\"symbol\":\"G1\",\"name\":\"v1 name\"}]");
        service.importSnapshot("hist-v2", "refseq", SourceFormat.JSON,
                "[{\"id\":\"N1\",\"type\":\"gene\",\"symbol\":\"G1\",\"name\":\"v2 name\"},"
                        + "{\"id\":\"N2\",\"type\":\"gene\",\"symbol\":\"G2\",\"name\":\"new in v2\"}]");

        // first published version stays current until explicit switch
        assertEquals("hist-v1", service.currentVersion().orElseThrow());
        assertEquals("v1 name", service.resolveIdentifier("N1").orElseThrow().canonicalName());
        assertTrue(service.resolveIdentifier("N2").isEmpty(), "N2 does not exist in v1 view");

        assertTrue(service.switchVersion("hist-v2"));
        assertEquals("hist-v2", service.currentVersion().orElseThrow());
        assertEquals("v2 name", service.resolveIdentifier("N1").orElseThrow().canonicalName());
        assertTrue(service.resolveIdentifier("N2").isPresent());

        // rollback: historical conclusion is exactly reproducible
        assertTrue(service.switchVersion("hist-v1"));
        assertEquals("v1 name", service.resolveIdentifier("N1").orElseThrow().canonicalName());
        assertTrue(service.resolveIdentifier("N2").isEmpty());

        // unknown version cannot be switched to
        assertFalse(service.switchVersion("does-not-exist"));
        log.info("version switch/rollback verified, retained versions={}", service.versions());
    }
}
