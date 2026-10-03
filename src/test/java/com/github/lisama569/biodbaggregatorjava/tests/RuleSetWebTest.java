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

/** Web surface for rule sets: list, switch, rule-scoped reads, comparison, bad rules. */
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
    void ruleEndpointsListSwitchCompareAndRejectUnknown() throws Exception {
        String refseq = "[{\"id\":\"NM1\",\"type\":\"gene\",\"symbol\":\"HBB\",\"name\":\"beta globin\","
                + "\"organism\":\"Homo sapiens\",\"xrefs\":{\"uniprot\":\"Q1\"}}]";
        String uniprot = "id,type,symbol,name,sequence,organism,xrefs\n"
                + "Q1,gene,HBB,a clearly longer uniprot authoritative name,,Homo sapiens,refseq:NM1\n";
        service.importSnapshot("w1", "refseq", SourceFormat.JSON, refseq);
        service.importSnapshot("w1", "uniprot", SourceFormat.CSV, uniprot);

        // list rule sets
        mvc.perform(get("/api/rules"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.current").value("priority-rules-v1"))
                .andExpect(jsonPath("$.ruleSets[?(@.id=='ensembl-first-v1')]").exists());

        // pinned historical read via REST
        mvc.perform(get("/api/snapshots/w1/pinned-rules"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pinnedRules").value("priority-rules-v1"));

        // alt rules produce a different winner without touching the pinned conclusion
        mvc.perform(get("/api/entities").param("identifier", "NM1")
                        .param("rules", "ensembl-first-v1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].rulesVersion").value("ensembl-first-v1"))
                .andExpect(jsonPath("$[0].entityKey").value("GENE:uniprot:Q1"));

        mvc.perform(get("/api/entities").param("identifier", "NM1"))
                .andExpect(jsonPath("$[0].rulesVersion").value("priority-rules-v1"))
                .andExpect(jsonPath("$[0].entityKey").value("GENE:refseq:NM1"));

        // comparison endpoint reports the divergence
        mvc.perform(get("/api/snapshots/w1/rule-comparison")
                        .param("rulesA", "priority-rules-v1")
                        .param("rulesB", "ensembl-first-v1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.identical").value(false))
                .andExpect(jsonPath("$.totalFieldWinnerChanges").value(org.hamcrest.Matchers.greaterThan(0)));
        log.info("web rule comparison verified for snapshot w1");

        // explicit resolution by rules
        mvc.perform(get("/api/snapshots/w1/resolution").param("rules", "ensembl-first-v1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rulesVersion").value("ensembl-first-v1"));

        // unknown rule set -> 404 with distinguishable reason code, not silent default
        mvc.perform(get("/api/snapshots/w1/resolution").param("rules", "ghost"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.reason").value("UNKNOWN_RULESET"));

        // switch to alt then publish a new version -> pinned to alt
        mvc.perform(post("/api/rules/ensembl-first-v1/switch"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.current").value("ensembl-first-v1"));
        mvc.perform(post("/api/snapshots/w2/imports")
                        .param("source", "uniprot")
                        .param("format", SourceFormat.CSV.name())
                        .contentType(MediaType.TEXT_PLAIN).content(uniprot))
                .andExpect(status().isOk());
        mvc.perform(get("/api/snapshots/w2/pinned-rules"))
                .andExpect(jsonPath("$.pinnedRules").value("ensembl-first-v1"));

        // unknown rule switch -> 404
        mvc.perform(post("/api/rules/ghost/switch"))
                .andExpect(status().isNotFound());
    }
}
