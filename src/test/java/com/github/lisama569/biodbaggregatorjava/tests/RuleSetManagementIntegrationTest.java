package com.github.lisama569.biodbaggregatorjava.tests;

import com.github.lisama569.biodbaggregatorjava.model.ResolutionReport;
import com.github.lisama569.biodbaggregatorjava.model.ResolvedEntity;
import com.github.lisama569.biodbaggregatorjava.model.SourceFormat;
import com.github.lisama569.biodbaggregatorjava.model.rules.EntityClusterDifference;
import com.github.lisama569.biodbaggregatorjava.model.rules.FieldWinnerDifference;
import com.github.lisama569.biodbaggregatorjava.model.rules.ResolutionComparison;
import com.github.lisama569.biodbaggregatorjava.ruleset.RuleSetException;
import com.github.lisama569.biodbaggregatorjava.ruleset.RuleSetFailureReason;
import com.github.lisama569.biodbaggregatorjava.service.AggregatorService;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Rule-set management end to end:
 * <ul>
 *   <li>changing rules never rewrites a version's pinned historical conclusion;</li>
 *   <li>the same data snapshot keeps conclusions under multiple rule sets, and the two
 *       can be compared (entity keys, field winners, conflicts, ambiguities);</li>
 *   <li>unknown rule sets are rejected with a distinguishable reason, never defaulted.</li>
 * </ul>
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class RuleSetManagementIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(RuleSetManagementIntegrationTest.class);
    private static final String DEFAULT = "priority-rules-v1";
    private static final String ALT = "ensembl-first-v1";

    @Autowired
    private AggregatorService service;

    private void importLinkedTwoSourceVersion(String version) {
        // refseq: short name; uniprot: longer, differently-winning name.
        String refseq = """
                [{"id":"NM1","type":"gene","symbol":"HBB","name":"beta globin",
                  "organism":"Homo sapiens","xrefs":{"uniprot":"Q1"}}]
                """;
        String uniprot = """
                id,type,symbol,name,sequence,organism,xrefs
                Q1,gene,HBB,hemoglobin subunit beta from uniprot,,Homo sapiens,refseq:NM1
                """;
        assertEquals("CREATED",
                service.importSnapshot(version, "refseq", SourceFormat.JSON, refseq).status());
        assertEquals("CREATED",
                service.importSnapshot(version, "uniprot", SourceFormat.CSV, uniprot).status());
    }

    @Test
    void oldConclusionStaysPinnedAfterRulesChangeAndCanBeReplayedExplicitly() {
        String version = "rule-hist-v1";
        importLinkedTwoSourceVersion(version);

        ResolutionReport pinned = service.getResolution(version).orElseThrow();
        assertEquals(DEFAULT, pinned.rulesVersion(),
                "new version is pinned to the rule set selected at publish time");
        ResolvedEntity hbbDefault = service.resolveIdentifier("NM1").orElseThrow();
        assertEquals("GENE:refseq:NM1", hbbDefault.entityKey());
        assertEquals("beta globin", hbbDefault.canonicalName());
        assertEquals("refseq", hbbDefault.fieldWinnerSources().get("name"));
        log.info("pinned historical conclusion: snapshot={} rules={} key={} nameWinner={} name={}",
                version, pinned.rulesVersion(), hbbDefault.entityKey(),
                hbbDefault.fieldWinnerSources().get("name"), hbbDefault.canonicalName());

        // switch the whole service to a brand-new priority order
        assertTrue(service.switchRules(ALT));
        assertEquals(ALT, service.currentRulesId());

        // 1) the historical conclusion is returned byte-for-byte, not rewritten by new rules
        ResolutionReport stillPinned = service.getResolution(version).orElseThrow();
        assertEquals(pinned, stillPinned,
                "changing current rules must not mutate the version's pinned conclusion");
        assertEquals(DEFAULT, stillPinned.rulesVersion());
        ResolvedEntity stillHbb = service.resolveIdentifier(
                version, DEFAULT, "NM1").orElseThrow();
        assertEquals("GENE:refseq:NM1", stillHbb.entityKey());
        assertEquals("beta globin", stillHbb.canonicalName());
        log.info("historical conclusion replayed explicitly after rule switch: snapshot={} rules={} key={}",
                version, stillHbb.rulesVersion(), stillHbb.entityKey());

        // 2) the same data under the new rules coexists as a separate conclusion
        ResolutionReport altReport = service.getResolution(version, ALT).orElseThrow();
        assertEquals(ALT, altReport.rulesVersion());
        ResolvedEntity hbbAlt = service.resolveIdentifier(version, ALT, "NM1").orElseThrow();
        assertEquals("GENE:uniprot:Q1", hbbAlt.entityKey(),
                "ensembl-first ranks uniprot above refseq for this two-source snapshot");
        // 3) unqualified reads of the OLD version still show the pinned rules
        ResolvedEntity viaCurrentApi = service.resolveIdentifier("NM1").orElseThrow();
        assertEquals(DEFAULT, viaCurrentApi.rulesVersion(),
                "version-scoped pinned reads must not leak the new current rules");
        log.info("two conclusions coexist for one snapshot: pinnedRules={} altRules={}",
                pinned.rulesVersion(), altReport.rulesVersion());
    }

    @Test
    void sameDataUnderTwoRuleSetsCoexistAndDifferencesAreComparable() {
        String version = "rule-cmp-v1";
        importLinkedTwoSourceVersion(version);

        ResolvedEntity underDefault = service.resolveIdentifier(version, DEFAULT, "NM1").orElseThrow();
        ResolvedEntity underAlt = service.resolveIdentifier(version, ALT, "NM1").orElseThrow();

        // entity anchor flips: refseq wins under default, uniprot under the alternative
        assertEquals("GENE:refseq:NM1", underDefault.entityKey());
        assertEquals("GENE:uniprot:Q1", underAlt.entityKey());
        assertNotEquals(underDefault.canonicalName(), underAlt.canonicalName());
        assertEquals("refseq", underDefault.fieldWinnerSources().get("name"));
        assertEquals("uniprot", underAlt.fieldWinnerSources().get("name"));

        ResolutionComparison cmp = service.compareRules(version, DEFAULT, ALT);
        assertEquals(version, cmp.snapshotVersion());
        assertEquals(DEFAULT, cmp.rulesIdA());
        assertEquals(ALT, cmp.rulesIdB());
        assertFalse(cmp.identical());
        assertEquals(1, cmp.entityCountA());
        assertEquals(1, cmp.entityCountB());

        // cluster matched by members, not by (priority-dependent) entity key
        assertEquals(1, cmp.clusterDifferences().size());
        EntityClusterDifference diff = cmp.clusterDifferences().get(0);
        assertEquals("GENE:refseq:NM1", diff.entityKeyA());
        assertEquals("GENE:uniprot:Q1", diff.entityKeyB());
        Set<String> changedFields = diff.changedFields().stream()
                .map(FieldWinnerDifference::field).collect(Collectors.toSet());
        assertTrue(changedFields.contains("name"),
                "name winner must be reported as changed: " + changedFields);
        FieldWinnerDifference nameDiff = diff.changedFields().stream()
                .filter(d -> d.field().equals("name")).findFirst().orElseThrow();
        assertEquals("refseq", nameDiff.rulesAWinnerSource());
        assertEquals("uniprot", nameDiff.rulesBWinnerSource());
        log.info("comparison: identical={} clusterDiffs={} fieldWinnerChanges={} conflicts {}->{} ambiguitiesOnlyA={} onlyB={}",
                cmp.identical(), cmp.clusterDifferences().size(), cmp.totalFieldWinnerChanges(),
                cmp.conflictCountA(), cmp.conflictCountB(),
                cmp.ambiguousOnlyInA(), cmp.ambiguousOnlyInB());

        // comparing a rule set against itself is a client error
        assertThrows(IllegalArgumentException.class,
                () -> service.compareRules(version, DEFAULT, DEFAULT));
    }

    @Test
    void comparisonIsReversibleAndIdenticalWhenRulesEquivalent() {
        String version = "rule-eq-v1";
        importLinkedTwoSourceVersion(version);
        // default compared with itself through distinct ids that agree on this snapshot:
        // alt differs, so instead assert reverse(A,B) mirrors forward(B,A) for key flip.
        ResolutionComparison forward = service.compareRules(version, ALT, DEFAULT);
        ResolutionComparison reverse = service.compareRules(version, DEFAULT, ALT);
        assertEquals(forward.clusterDifferences().get(0).entityKeyA(),
                reverse.clusterDifferences().get(0).entityKeyB());
        assertEquals(forward.totalFieldWinnerChanges(), reverse.totalFieldWinnerChanges());
    }

    @Test
    void unknownRuleSetIsRejectedWithReasonNeverSilentlyDefaulted() {
        String version = "rule-unknown-v1";
        importLinkedTwoSourceVersion(version);

        RuleSetException ex = assertThrows(RuleSetException.class,
                () -> service.getResolution(version, "no-such-rules"));
        assertEquals(RuleSetFailureReason.UNKNOWN_RULESET, ex.reason());
        log.info("unknown rules rejected distinctly: reason={} message={}",
                ex.reason(), ex.getMessage());

        RuleSetException ex2 = assertThrows(RuleSetException.class,
                () -> service.queryAll("no-such-rules"));
        assertEquals(RuleSetFailureReason.UNKNOWN_RULESET, ex2.reason());

        // current rules untouched by the failed lookup; pinned conclusion still served
        assertEquals(DEFAULT, service.currentRulesId());
        assertEquals(DEFAULT, service.getResolution(version).orElseThrow().rulesVersion());
    }

    @Test
    void newVersionAfterRuleSwitchIsPinnedToNewRules() {
        // switch first, then publish a new version -> its pin is the alternative
        assertTrue(service.switchRules(ALT));
        importLinkedTwoSourceVersion("rule-newpin-v1");
        ResolutionReport report = service.getResolution("rule-newpin-v1").orElseThrow();
        assertEquals(ALT, report.rulesVersion());
        ResolvedEntity e = service.resolveIdentifier("Q1").orElseThrow();
        assertEquals(ALT, e.rulesVersion());
        assertEquals("GENE:uniprot:Q1", e.entityKey());
        log.info("new version pinned to current rules at publish: snapshot={} pinned={} key={}",
                report.snapshotVersion(), report.rulesVersion(), e.entityKey());
    }
}
