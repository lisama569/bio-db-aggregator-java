package com.github.lisama569.biodbaggregatorjava.tests;

import com.github.lisama569.biodbaggregatorjava.model.SourceFormat;
import com.github.lisama569.biodbaggregatorjava.service.AggregatorService;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** REST surface for rule listing, activation, rule-scoped resolution and comparison. */
@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class RuleSetWebTest {

    private static final Logger log = LoggerFactory.getLogger(RuleSetWebTest.class);

    @Autowired
    private MockMvc mvc;
    @Autowired
    private AggregatorService service;

    @Test
    void ruleEndpointsAndScopedResolutionWork() throws Exception {
        service.importSnapshot("w1", "refseq", SourceFormat.JSON,
                "[{\"id\":\"NM1\",\"type\":\"gene\",\"symbol\":\"HBB\","
                        + "\"name\":\"refseq name\",\"xrefs\":{\"ensembl\":\"E1\"}}]",
                "priority-rules-v1");
        service.importSnapshot("w1", "ensembl", SourceFormat.FLAT,
                "REC|E1|gene|HBB|ensembl name|||refseq:NM1\n",
                "priority-rules-v1");

        // list rule sets: shipped two are visible, default active
        mvc.perform(get("/api/rules"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value("priority-rules-v1"))
                .andExpect(jsonPath("$.ruleSets[?(@.id=='priority-rules-ensembl-v1')]")
                        .exists());

        // pinned resolution uses refseq winner
        mvc.perform(get("/api/snapshots/w1/resolution"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rulesVersion").value("priority-rules-v1"))
                .andExpect(jsonPath("$.entities[0].canonicalName").value("refseq name"));

        // explicit ensembl rules -> ensembl winner, pinned untouched
        mvc.perform(get("/api/snapshots/w1/resolution").param("rules", "priority-rules-ensembl-v1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rulesVersion").value("priority-rules-ensembl-v1"))
                .andExpect(jsonPath("$.entities[0].canonicalName").value("ensembl name"));
        mvc.perform(get("/api/snapshots/w1/resolution"))
                .andExpect(jsonPath("$.entities[0].canonicalName").value("refseq name"));

        // comparison flags both:
        //  - symbol keeps the SAME value "HBB" but its credited winner flips
        //    refseq -> ensembl, which must still be reported (attribution change);
        //  - name changes both winner and value.
        mvc.perform(get("/api/snapshots/w1/resolution/compare")
                        .param("rulesA", "priority-rules-v1")
                        .param("rulesB", "priority-rules-ensembl-v1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.equivalent").value(false))
                .andExpect(jsonPath(
                        "$.fieldDifferences[?(@.field=='symbol')].kind")
                        .value(org.hamcrest.Matchers.everyItem(
                                org.hamcrest.Matchers.is("WINNER_SOURCE_CHANGED"))))
                .andExpect(jsonPath(
                        "$.fieldDifferences[?(@.field=='symbol')].winnerValueA")
                        .value(org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is("HBB"))))
                .andExpect(jsonPath(
                        "$.fieldDifferences[?(@.field=='symbol')].winnerValueB")
                        .value(org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is("HBB"))))
                .andExpect(jsonPath(
                        "$.fieldDifferences[?(@.field=='symbol')].winnerSourceA")
                        .value(org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is("refseq"))))
                .andExpect(jsonPath(
                        "$.fieldDifferences[?(@.field=='symbol')].winnerSourceB")
                        .value(org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is("ensembl"))))
                .andExpect(jsonPath(
                        "$.fieldDifferences[?(@.field=='name')].kind")
                        .value(org.hamcrest.Matchers.everyItem(
                                org.hamcrest.Matchers.is("VALUE_AND_SOURCE_CHANGED"))));

        // scoped entity query
        mvc.perform(get("/api/entities/NM1").param("rules", "priority-rules-ensembl-v1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canonicalName").value("ensembl name"))
                .andExpect(jsonPath("$.rulesVersion").value("priority-rules-ensembl-v1"));

        // activate unknown -> 404
        mvc.perform(post("/api/rules/nope/activate"))
                .andExpect(status().isNotFound());
        // activate known -> 200
        mvc.perform(post("/api/rules/priority-rules-ensembl-v1/activate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activated").value(true));
        // version list advertises active rules
        mvc.perform(get("/api/snapshots"))
                .andExpect(jsonPath("$.activeRules").value("priority-rules-ensembl-v1"));

        // import with mismatched rules -> 409 RULE_SET_MISMATCH
        mvc.perform(post("/api/snapshots/w1/imports")
                        .param("source", "uniprot").param("format", SourceFormat.CSV.name())
                        .param("rules", "priority-rules-ensembl-v1")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("id,type,symbol\nU1,gene,HBB"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value("RULE_SET_MISMATCH"))
                .andExpect(jsonPath("$.pinnedRuleSetId").value("priority-rules-v1"));

        // unknown rule set param -> 400 distinguishable error
        mvc.perform(get("/api/snapshots/w1/resolution").param("rules", "ghost"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("UNKNOWN_RULE_SET"));

        // reload endpoint reports catalog
        mvc.perform(post("/api/rules/reload"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").exists())
                .andExpect(jsonPath("$.failures").isArray());

        log.info("rule web endpoints verified");
    }
}
