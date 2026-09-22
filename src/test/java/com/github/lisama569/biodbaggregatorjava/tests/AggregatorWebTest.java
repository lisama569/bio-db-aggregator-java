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

/** Web-layer tests: import endpoint, idempotency, 409 conflict, version switch, queries. */
@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class AggregatorWebTest {

    private static final Logger log = LoggerFactory.getLogger(AggregatorWebTest.class);

    @Autowired
    private MockMvc mvc;
    @Autowired
    private AggregatorService service;

    private static final String JSON = """
            [
              {"id":"N1","type":"gene","symbol":"G1","name":"gene one",
               "xrefs":{"ensembl":"E1"}},
              {"id":"","type":"gene","symbol":"NOID","name":"blank id rejected"}
            ]
            """;

    @Test
    void importIsIdempotentAndConflictReturns409() throws Exception {
        log.info("WEB TEST input snapshot:\n{}", JSON.strip());
        mvc.perform(post("/api/snapshots/v1/imports")
                        .param("source", "refseq")
                        .param("format", SourceFormat.JSON.name())
                        .contentType(MediaType.TEXT_PLAIN)
                        .content(JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CREATED"))
                .andExpect(jsonPath("$.acceptedCount").value(1))
                .andExpect(jsonPath("$.rejectedCount").value(1))
                .andExpect(jsonPath("$.duplicateImport").value(false));

        // identical replay -> idempotent
        mvc.perform(post("/api/snapshots/v1/imports")
                        .param("source", "refseq")
                        .param("format", SourceFormat.JSON.name())
                        .contentType(MediaType.TEXT_PLAIN)
                        .content(JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DUPLICATE"))
                .andExpect(jsonPath("$.duplicateImport").value(true));

        // different content -> 409, not a silent overwrite
        String tampered = JSON.replace("gene one", "tampered");
        mvc.perform(post("/api/snapshots/v1/imports")
                        .param("source", "refseq")
                        .param("format", SourceFormat.JSON.name())
                        .contentType(MediaType.TEXT_PLAIN)
                        .content(tampered))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value("CONFLICTING_REIMPORT"));

        // original value remains queryable
        mvc.perform(get("/api/entities").param("identifier", "N1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].canonicalName").value("gene one"));
    }

    @Test
    void emptyStoreSnapshotListDoesNotFail() throws Exception {
        mvc.perform(get("/api/snapshots"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.versions").isArray())
                .andExpect(jsonPath("$.current").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void versionSwitchAndSnapshotEndpointsWork() throws Exception {
        service.importSnapshot("r1", "refseq", SourceFormat.JSON,
                "[{\"id\":\"N1\",\"type\":\"gene\",\"symbol\":\"G1\",\"name\":\"rev1\"}]");
        service.importSnapshot("r2", "refseq", SourceFormat.JSON,
                "[{\"id\":\"N1\",\"type\":\"gene\",\"symbol\":\"G1\",\"name\":\"rev2\"}]");

        mvc.perform(get("/api/snapshots"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.versions[0]").value("r1"))
                .andExpect(jsonPath("$.current").value("r1"));

        mvc.perform(post("/api/snapshots/r2/switch"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.switched").value(true));
        mvc.perform(get("/api/entities/N1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.snapshotVersion").value("r2"))
                .andExpect(jsonPath("$.canonicalName").value("rev2"));

        // rollback reproduces the historical conclusion
        mvc.perform(post("/api/snapshots/r1/switch"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/entities/N1"))
                .andExpect(jsonPath("$.canonicalName").value("rev1"))
                .andExpect(jsonPath("$.rulesVersion").value("priority-rules-v1"));

        // unknown version switch -> 404
        mvc.perform(post("/api/snapshots/nope/switch"))
                .andExpect(status().isNotFound());
    }
}
