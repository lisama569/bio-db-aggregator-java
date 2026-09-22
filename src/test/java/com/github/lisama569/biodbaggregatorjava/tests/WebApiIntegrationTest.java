package com.github.lisama569.biodbaggregatorjava.tests;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.lisama569.biodbaggregatorjava.service.AggregationService;
import com.github.lisama569.biodbaggregatorjava.service.SnapshotImporter;
import com.github.lisama569.biodbaggregatorjava.snapshot.SnapshotInput;
import com.github.lisama569.biodbaggregatorjava.snapshot.SourceFormat;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 端到端集成测试：Spring 容器装配 -> REST 导入 -> 查询 -> 回退。
 */
@SpringBootTest
@AutoConfigureMockMvc
class WebApiIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(WebApiIntegrationTest.class);

    @Autowired
    private MockMvc mvc;
    @Autowired
    private SnapshotImporter importer;
    @Autowired
    private AggregationService aggregationService;
    private final ObjectMapper json = new ObjectMapper();

    private List<SnapshotInput> inputs(String ensemblResource) {
        return List.of(
                SnapshotTestSupport.input("ncbi-snapshots", SourceFormat.CSV, "ncbi-v1.csv"),
                SnapshotTestSupport.input("uniprot-snapshots", SourceFormat.TSV,
                        "uniprot-v1.tsv"),
                SnapshotTestSupport.input("ensembl-snapshots", SourceFormat.JSONL,
                        ensemblResource));
    }

    @Test
    void importQueryRollbackViaHttp() throws Exception {
        // 通过 service 预置 v1 / v2 两个版本（HTTP 导入体同样受支持，此处复用夹具）
        var v1 = importer.importSnapshot(inputs("ensembl-v1.jsonl"));
        var v2 = importer.importSnapshot(inputs("ensembl-v2.jsonl"));
        log.info("[test:webapi] v1={} v2={} rulesVersion={}",
                v1.version(), v2.version(), importer.rules().rulesVersion());

        // 当前为 v2
        mvc.perform(get("/api/entities/uniprot/P04637"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canonicalId").value("uniprot:P04637"))
                .andExpect(jsonPath("$.attributes.name.chosen")
                        .value("p53 isoform a v2"));

        // 被拒绝记录可经 HTTP 查询，原因可区分
        MvcResult rejected = mvc.perform(get("/api/rejected")).andExpect(status().isOk())
                .andReturn();
        JsonNode rejectedNode = json.readTree(rejected.getResponse().getContentAsString());
        log.info("[test:webapi] rejected count={}", rejectedNode.size());

        // 回退到 v1
        mvc.perform(post("/api/snapshots/rollback/" + v1.version()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rolledBackTo").value(v1.version()));
        mvc.perform(get("/api/entities/uniprot/P04637"))
                .andExpect(jsonPath("$.attributes.name.chosen")
                        .value("p53 isoform a"));

        // 版本列表
        mvc.perform(get("/api/snapshots/versions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));

        // HTTP 导入幂等
        String body = json.writeValueAsString(new java.util.HashMap<String, Object>() {{
            put("sources", List.of(
                    source("ncbi-snapshots", "CSV",
                            SnapshotTestSupport.resource("ncbi-v1.csv")),
                    source("uniprot-snapshots", "TSV",
                            SnapshotTestSupport.resource("uniprot-v1.tsv")),
                    source("ensembl-snapshots", "JSONL",
                            SnapshotTestSupport.resource("ensembl-v1.jsonl"))));
        }});
        mvc.perform(post("/api/snapshots/import")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.idempotentReuse").value(true))
                .andExpect(jsonPath("$.version").value(v1.version()));
    }

    private java.util.Map<String, String> source(String s, String f, String c) {
        return java.util.Map.of("source", s, "format", f, "content", c);
    }
}
