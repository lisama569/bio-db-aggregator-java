package com.github.lisama569.biodbaggregatorjava.tests;

import com.github.lisama569.biodbaggregatorjava.model.EntityClusterDifference;
import com.github.lisama569.biodbaggregatorjava.model.FieldDecisionDifference;
import com.github.lisama569.biodbaggregatorjava.model.ImportReport;
import com.github.lisama569.biodbaggregatorjava.model.ResolutionComparison;
import com.github.lisama569.biodbaggregatorjava.model.ResolutionReport;
import com.github.lisama569.biodbaggregatorjava.model.ResolvedEntity;
import com.github.lisama569.biodbaggregatorjava.model.SourceFormat;
import com.github.lisama569.biodbaggregatorjava.service.AggregatorService;
import com.github.lisama569.biodbaggregatorjava.service.DefaultAggregatorService;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end rule-set features:
 * <ul>
 *   <li>one data snapshot co-existing under two rule sets, both conclusions retained;</li>
 *   <li>changing/switching rules never rewrites a version's pinned old conclusion;</li>
 *   <li>explicit reproduction under a named rule set;</li>
 *   <li>differences (merge / field winners / conflicts / ambiguities) are comparable;</li>
 *   <li>pinning another rule set onto an existing version is refused, not silently applied.</li>
 * </ul>
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class RuleSetReproducibilityIntegrationTest {

    private static final Logger log =
            LoggerFactory.getLogger(RuleSetReproducibilityIntegrationTest.class);

    private static final String RULES_REFSEQ = "priority-rules-v1";
    private static final String RULES_ENSEMBL = "priority-rules-ensembl-v1";

    @Autowired
    private AggregatorService service;

    /** HBB gene present in refseq and ensembl, with differing non-blank names. */
    private static final String REFSEQ = """
            [
              {"id":"NM1","type":"gene","symbol":"HBB","name":"RefSeq canonical name",
               "organism":"Homo sapiens","xrefs":{"ensembl":"E1"}}
            ]
            """;

    private static final String ENSEMBL = """
            REC|E1|gene|HBB|Ensembl long name||Homo sapiens|refseq:NM1
            """;

    private void importBothSources(String version, String rules) {
        ImportReport r1 = service.importSnapshot(version, "refseq", SourceFormat.JSON,
                REFSEQ, rules);
        assertEquals("CREATED", r1.status(), "first import pins the requested rule set");
        assertEquals(rules, r1.pinnedRuleSetId());
        ImportReport r2 = service.importSnapshot(version, "ensembl", SourceFormat.FLAT,
                ENSEMBL, rules);
        // further imports keep the pinned rule set even when naming the same one
        assertEquals("CREATED", r2.status());
        assertEquals(rules, r2.pinnedRuleSetId());
    }

    @Test
    void sameDataCoexistsUnderTwoRuleSetsAndBothConclusionsAreKept() {
        importBothSources("v-multi", RULES_REFSEQ);

        ResolutionReport pinned = service.getResolution("v-multi").orElseThrow();
        ResolutionReport underRefSeq = service.getResolution("v-multi", RULES_REFSEQ);
        ResolutionReport underEnsembl = service.getResolution("v-multi", RULES_ENSEMBL);

        log.info("Pinned/RefSeq rules={} name winner value='{}' conflicts={}",
                pinned.rulesVersion(),
                pinned.entities().get(0).canonicalName(),
                pinned.entities().get(0).fieldConflicts().size());
        log.info("Ensembl rules={} name winner value='{}' conflicts={}",
                underEnsembl.rulesVersion(),
                underEnsembl.entities().get(0).canonicalName(),
                underEnsembl.entities().get(0).fieldConflicts().size());

        // both rule sets merge the same two records into one entity
        assertEquals(1, pinned.entities().size());
        assertEquals(1, underEnsembl.entities().size());
        // but the field winner differs
        assertEquals("RefSeq canonical name",
                pinned.entities().get(0).canonicalName());
        assertEquals("Ensembl long name",
                underEnsembl.entities().get(0).canonicalName());
        assertEquals(RULES_REFSEQ, pinned.rulesVersion());
        assertEquals(RULES_ENSEMBL, underEnsembl.rulesVersion());

        // the pinned conclusion is still the refseq one after the alternative was computed
        ResolutionReport pinnedAgain = service.getResolution("v-multi").orElseThrow();
        assertEquals("RefSeq canonical name",
                pinnedAgain.entities().get(0).canonicalName(),
                "computing an alternative rule conclusion must not overwrite the pinned one");
        assertEquals(pinned.entities(), underRefSeq.entities());

        // ---- comparison ----
        ResolutionComparison diff =
                service.compareResolutions("v-multi", RULES_REFSEQ, RULES_ENSEMBL);
        log.info("comparison: equivalent={} fieldDiffs={} clusterDiffs={}",
                diff.equivalent(), diff.fieldDifferences().size(),
                diff.clusterDifferences().size());
        assertFalse(diff.equivalent());
        assertTrue(diff.clusterDifferences().isEmpty(),
                "xref merging is priority independent; same entity merges under both");
        FieldDecisionDifference nameDiff = diff.fieldDifferences().stream()
                .filter(d -> d.field().equals("name")).findFirst().orElseThrow();
        assertEquals("refseq", nameDiff.winnerSourceA());
        assertEquals("ensembl", nameDiff.winnerSourceB());
        assertEquals("RefSeq canonical name", nameDiff.winnerValueA());
        assertEquals("Ensembl long name", nameDiff.winnerValueB());
        // loser/winner swap moves the conflict between the two sides
        log.info("conflictsOnlyUnderA={} conflictsOnlyUnderB={}",
                diff.conflictsOnlyUnderA(), diff.conflictsOnlyUnderB());
        assertTrue(diff.conflictsOnlyUnderA().stream().anyMatch(s -> s.contains("name"))
                || diff.conflictsOnlyUnderB().stream().anyMatch(s -> s.contains("name")),
                "conflict attribution must change with the winner");

        // identical comparison is equivalent and empty
        ResolutionComparison self =
                service.compareResolutions("v-multi", RULES_REFSEQ, RULES_REFSEQ);
        assertTrue(self.equivalent());
    }

    @Test
    void changingActiveRulesDoesNotRewriteOldPinnedConclusions() {
        importBothSources("v-old", RULES_REFSEQ);
        assertEquals("RefSeq canonical name",
                service.getResolution("v-old").orElseThrow()
                        .entities().get(0).canonicalName());

        // switch the whole server to ensembl-first rules
        assertTrue(service.activateRuleSet(RULES_ENSEMBL));
        assertEquals(RULES_ENSEMBL, service.activeRuleSet().orElseThrow().id());

        // publish a NEW version; first publication explicitly pins the new active rule set
        importBothSources("v-new", RULES_ENSEMBL);
        assertEquals(RULES_ENSEMBL,
                service.getResolution("v-new").orElseThrow().rulesVersion());
        assertEquals("Ensembl long name",
                service.getResolution("v-new").orElseThrow()
                        .entities().get(0).canonicalName());

        // switch current back to the old version and fetch its stamped conclusion:
        // it must be the historical refseq outcome, not rewritten by the new active rules.
        assertTrue(service.switchVersion("v-old"));
        ResolutionReport old = service.getResolution("v-old").orElseThrow();
        assertEquals(RULES_REFSEQ, old.rulesVersion());
        assertEquals("RefSeq canonical name",
                old.entities().get(0).canonicalName(),
                "old conclusion must survive an active rule-set change");
        ResolvedEntity viaIdentifier = service.resolveIdentifier("NM1").orElseThrow();
        assertEquals(RULES_REFSEQ, viaIdentifier.rulesVersion());
        assertEquals("RefSeq canonical name", viaIdentifier.canonicalName());

        // explicit reproduction under the new rules is still available, side by side
        ResolutionReport oldUnderNewRules = service.getResolution("v-old", RULES_ENSEMBL);
        assertEquals("Ensembl long name",
                oldUnderNewRules.entities().get(0).canonicalName());
        // and does not disturb the pinned historical report
        assertEquals("RefSeq canonical name",
                service.getResolution("v-old").orElseThrow()
                        .entities().get(0).canonicalName());
        log.info("historical reproduction verified: v-old pinned={}, explicit ensembl name='{}'",
                old.rulesVersion(), oldUnderNewRules.entities().get(0).canonicalName());
    }

    @Test
    void republishingOldVersionWithAnotherRuleSetIsRefused() {
        importBothSources("v-pin", RULES_REFSEQ);
        // naming a different rule set for the already pinned version is a 409-class refusal
        ImportReport mismatch = service.importSnapshot("v-pin", "uniprot", SourceFormat.CSV,
                "id,type,symbol\nU1,gene,HBB", RULES_ENSEMBL);
        assertEquals(DefaultAggregatorService.RULE_SET_MISMATCH, mismatch.status());
        assertEquals(RULES_REFSEQ, mismatch.pinnedRuleSetId(),
                "the refusal reports the rule set actually pinned");
        // uniprot source must not have been added
        assertNotNull(service.getResolution("v-pin").orElseThrow());
        assertEquals(List.of("ensembl", "refseq"),
                service.getResolution("v-pin").orElseThrow().entities().get(0)
                        .contributingSources().stream().sorted().toList());

        // naming the SAME pinned rule set remains allowed (idempotent replay semantics)
        ImportReport ok = service.importSnapshot("v-pin", "refseq", SourceFormat.JSON,
                REFSEQ, RULES_REFSEQ);
        assertEquals("DUPLICATE", ok.status());
        log.info("rule pinning mismatch refused correctly, pinned stays {}",
                ok.pinnedRuleSetId());
    }

    @Test
    void alternativeConclusionIsRecomputedWhenVersionGetsNewSources() {
        importBothSources("v-grow", RULES_REFSEQ);
        // compute and cache an ensembl-first conclusion
        assertEquals("Ensembl long name",
                service.getResolution("v-grow", RULES_ENSEMBL)
                        .entities().get(0).canonicalName());

        // add a third source to the already published (pinned) version
        ImportReport added = service.importSnapshot("v-grow", "uniprot", SourceFormat.CSV,
                "id,type,symbol,name,sequence,organism,xrefs\n"
                        + "Q1,gene,HBB,UniProt middle name,,Homo sapiens,refseq:NM1",
                RULES_REFSEQ);
        assertEquals("CREATED", added.status());

        // both pinned and cached alternative conclusions now include the new member
        ResolutionReport ens = service.getResolution("v-grow", RULES_ENSEMBL);
        assertEquals(List.of("ensembl", "refseq", "uniprot"),
                ens.entities().get(0).contributingSources().stream().sorted().toList());
        assertEquals("Ensembl long name", ens.entities().get(0).canonicalName(),
                "ensembl still wins name, but the new source participates");
        ResolutionReport pinned = service.getResolution("v-grow").orElseThrow();
        assertEquals(List.of("ensembl", "refseq", "uniprot"),
                pinned.entities().get(0).contributingSources().stream().sorted().toList());
        log.info("alternative conclusion refreshed after growth: members={}",
                ens.entities().get(0).contributingSources());
    }

    @Test
    void unknownRuleSetIsRejectedNotDefaulted() {
        importBothSources("v-unk", RULES_REFSEQ);
        RuntimeException ex = org.junit.jupiter.api.Assertions.assertThrows(
                com.github.lisama569.biodbaggregatorjava.rules.UnknownRuleSetException.class,
                () -> service.getResolution("v-unk", "no-such-rules"));
        assertFalse(ex.getMessage().isBlank());
        log.info("unknown rule set refused: {}", ex.getMessage());
    }

    @Test
    void strictRuleSetRefusesUndeclaredSourcesWithDistinguishableReason() {
        // genbank source is not declared in the strict rule set
        String genbank = "[{\"id\":\"GB1\",\"type\":\"gene\",\"symbol\":\"HBB\","
                + "\"name\":\"genbank name\",\"xrefs\":{\"refseq\":\"NM1\"}}]";
        com.github.lisama569.biodbaggregatorjava.rules.InvalidRuleSetException ex =
                org.junit.jupiter.api.Assertions.assertThrows(
                        com.github.lisama569.biodbaggregatorjava.rules.InvalidRuleSetException.class,
                        () -> {
                            service.importSnapshot("v-strict", "genbank", SourceFormat.JSON,
                                    genbank, "test-rules-strict");
                            service.getResolution("v-strict", "test-rules-strict");
                        });
        assertEquals(
                com.github.lisama569.biodbaggregatorjava.rules.RuleRejectionReason
                        .UNKNOWN_SOURCE_REFERENCED,
                ex.reason());
        log.info("strict rule refusal: reason={} message={}", ex.reason(), ex.getMessage());
    }
}
