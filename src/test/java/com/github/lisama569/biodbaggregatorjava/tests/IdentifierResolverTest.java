package com.github.lisama569.biodbaggregatorjava.tests;

import com.github.lisama569.biodbaggregatorjava.model.AmbiguousLink;
import com.github.lisama569.biodbaggregatorjava.model.BioRecord;
import com.github.lisama569.biodbaggregatorjava.model.EntityType;
import com.github.lisama569.biodbaggregatorjava.model.ResolutionConflict;
import com.github.lisama569.biodbaggregatorjava.model.ResolutionReport;
import com.github.lisama569.biodbaggregatorjava.model.ResolvedEntity;
import com.github.lisama569.biodbaggregatorjava.model.Snapshot;
import com.github.lisama569.biodbaggregatorjava.resolution.PriorityIdentifierResolver;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cross-source merging and deterministic priority tests.
 * Logs every input record, the snapshot/rules version and the decision basis.
 */
class IdentifierResolverTest {

    private static final Logger log = LoggerFactory.getLogger(IdentifierResolverTest.class);

    private final PriorityIdentifierResolver resolver = new PriorityIdentifierResolver(
            "refseq,uniprot,ensembl");

    private static BioRecord rec(String source, EntityType type, String id, String symbol,
                                 String name, String sequence, Map<String, String> xrefs) {
        return new BioRecord(source + "#" + id, source, type, id, symbol, name, sequence,
                "Homo sapiens", Map.copyOf(xrefs));
    }

    private ResolutionReport resolveAndLog(String version, List<BioRecord> records) {
        log.info("resolver input: snapshotVersion={} rulesVersion={} recordCount={}",
                version, resolver.rulesVersion(), records.size());
        records.forEach(r -> log.info("  input record: source={} type={} localId={} symbol={} name={} sequence={} xrefs={}",
                r.source(), r.type(), r.localId(), r.symbol(), r.name(), r.sequence(), r.crossReferences()));
        Snapshot snapshot = new Snapshot(version, List.copyOf(records), List.of(), List.of());
        ResolutionReport report = resolver.resolve(snapshot);
        report.entities().forEach(e -> log.info(
                "  resolved: entityKey={} symbol={} name={} sequence={} identifiers={} conflicts={}",
                e.entityKey(), e.canonicalSymbol(), e.canonicalName(), e.sequence(),
                e.identifiersBySource(),
                e.fieldConflicts().stream().map(c -> c.field() + "<-" + c.winnerSource()
                        + "(losers=" + c.loserSources() + ")").toList()));
        report.ambiguities().forEach(a -> log.info("  ambiguity kept: {} {} claimer={}:{} owner={}:{}",
                a.reason(), a.token(), a.claimerSource(), a.claimerId(), a.ownerSource(), a.ownerId()));
        return report;
    }

    @Test
    void mergesAcrossSourcesByXrefAndAppliesPriority() {
        List<BioRecord> records = new ArrayList<>();
        // ensembl first on purpose: outcome must not depend on record order
        records.add(rec("ensembl", EntityType.GENE, "ENSG1", "HBB",
                "ensembl long name", null, Map.of("refseq", "NM1")));
        records.add(rec("uniprot", EntityType.GENE, "Q1", "HBB",
                "uniprot name", null, Map.of("ensembl", "ENSG1")));
        records.add(rec("refseq", EntityType.GENE, "NM1", "HBB",
                "refseq authoritative name", null, Map.of("ensembl", "ENSG1")));

        ResolutionReport report = resolveAndLog("v-test", records);

        assertEquals(1, report.entities().size(), "three sources merge into one entity");
        ResolvedEntity e = report.entities().get(0);
        assertEquals("GENE:refseq:NM1", e.entityKey(), "entity key anchored on priority winner");
        assertEquals("refseq authoritative name", e.canonicalName(),
                "highest-priority source wins the field");
        assertEquals(Map.of("refseq", "NM1", "uniprot", "Q1", "ensembl", "ENSG1"),
                e.identifiersBySource());
        assertEquals(List.of("ensembl", "refseq", "uniprot"), e.contributingSources().stream().sorted().toList());
        ResolutionConflict nameConflict = e.fieldConflicts().stream()
                .filter(c -> c.field().equals("name")).findFirst().orElseThrow();
        assertEquals("refseq", nameConflict.winnerSource());
        assertEquals("refseq authoritative name", nameConflict.winnerValue());
        assertEquals(List.of("uniprot", "ensembl"), nameConflict.loserSources(),
                "losers are ordered by source priority (uniprot ranks above ensembl)");
        assertEquals(List.of("uniprot name", "ensembl long name"), nameConflict.loserValues());
    }

    @Test
    void winnerSkipsBlankFieldsAndRecordsEveryConflict() {
        List<BioRecord> records = List.of(
                // refseq has no name -> uniprot wins name, but refseq still anchors key via presence
                rec("refseq", EntityType.PROTEIN, "NP1", "HBB_P", null, null,
                        Map.of("uniprot", "P1")),
                rec("uniprot", EntityType.PROTEIN, "P1", "HBB_P", "uniprot wins name", "SEQU",
                        Map.of("refseq", "NP1")),
                rec("ensembl", EntityType.PROTEIN, "ENSP1", "HBB_P", "ensembl other name", "SEQE",
                        Map.of("uniprot", "P1")));

        ResolutionReport report = resolveAndLog("v-blank", records);
        ResolvedEntity e = report.entities().get(0);
        assertEquals("PROTEIN:refseq:NP1", e.entityKey());
        assertEquals("uniprot wins name", e.canonicalName(),
                "blank refseq name is skipped; next priority source wins");
        assertEquals("SEQU", e.sequence(), "uniprot sequence beats ensembl by priority");
        Optional<ResolutionConflict> seqConflict = e.fieldConflicts().stream()
                .filter(c -> c.field().equals("sequence")).findFirst();
        assertTrue(seqConflict.isPresent());
        assertEquals(List.of("ensembl"), seqConflict.get().loserSources());
        assertEquals(List.of("SEQE"), seqConflict.get().loserValues());
    }

    @Test
    void crossTypeSameTokenIsKeptAsAmbiguityNotMerged() {
        List<BioRecord> records = List.of(
                rec("refseq", EntityType.GENE, "NM1", "G1", "gene record", null, Map.of()),
                rec("uniprot", EntityType.PROTEIN, "PX", "P1", "protein claims refseq:NM1",
                        null, Map.of("refseq", "NM1")));

        ResolutionReport report = resolveAndLog("v-cross", records);
        assertEquals(2, report.entities().size(), "gene and protein must not merge");
        assertEquals(1, report.ambiguities().size());
        AmbiguousLink link = report.ambiguities().get(0);
        assertEquals(AmbiguousLink.CROSS_TYPE_COLLISION, link.reason());
        assertEquals("refseq:NM1", link.token());
        assertEquals("refseq", link.ownerSource());
        assertEquals("uniprot", link.claimerSource());
        log.info("decision basis: cross-type claim not merged because entity types differ; {} recorded",
                link.reason());
    }

    @Test
    void danglingReferencesAreKeptAsAmbiguityNotSilentlyDropped() {
        List<BioRecord> records = List.of(
                rec("ensembl", EntityType.PROTEIN, "ENSP5", "DANGLING_P", "d", "MKKK",
                        Map.of("uniprot", "P00000")));
        ResolutionReport report = resolveAndLog("v-dangling", records);
        assertEquals(1, report.entities().size(), "claimer still exists as its own entity");
        assertEquals(1, report.ambiguities().size());
        assertEquals(AmbiguousLink.DANGLING_REFERENCE, report.ambiguities().get(0).reason());
    }

    @Test
    void conclusionsAreReproducibleRegardlessOfOrder() {
        List<BioRecord> a = List.of(
                rec("refseq", EntityType.GENE, "NM1", "HBB", "rname", null, Map.of("ensembl", "E1")),
                rec("uniprot", EntityType.GENE, "Q1", "HBB", "uname", null, Map.of("ensembl", "E1")),
                rec("ensembl", EntityType.GENE, "E1", "HBB", "ename", null, Map.of("refseq", "NM1")));
        List<BioRecord> shuffled = new ArrayList<>(a);
        java.util.Collections.reverse(shuffled);

        ResolutionReport r1 = resolveAndLog("v-repro", a);
        ResolutionReport r2 = resolveAndLog("v-repro", shuffled);
        assertEquals(r1.entities(), r2.entities(), "same snapshot -> identical conclusions");
        assertEquals(r1.ambiguities(), r2.ambiguities());
        assertEquals(resolver.rulesVersion(), r1.rulesVersion(),
                "conclusions are stamped with the rules version");
    }
}
