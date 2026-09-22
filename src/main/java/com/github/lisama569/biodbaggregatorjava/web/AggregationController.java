package com.github.lisama569.biodbaggregatorjava.web;

import com.github.lisama569.biodbaggregatorjava.model.ImportReport;
import com.github.lisama569.biodbaggregatorjava.model.ResolvedEntity;
import com.github.lisama569.biodbaggregatorjava.service.AggregationService;
import com.github.lisama569.biodbaggregatorjava.service.SnapshotImporter;
import com.github.lisama569.biodbaggregatorjava.snapshot.SnapshotInput;
import com.github.lisama569.biodbaggregatorjava.snapshot.SourceFormat;
import com.github.lisama569.biodbaggregatorjava.store.SnapshotInfo;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * REST 接口（本地验证用）：
 * <ul>
 *   <li>POST /api/snapshots/import 导入一组来源快照（幂等）；</li>
 *   <li>GET  /api/snapshots/current 查看当前版本信息；</li>
 *   <li>GET  /api/snapshots/versions 列出全部版本；</li>
 *   <li>POST /api/snapshots/rollback/{version} 回退到历史版本；</li>
 *   <li>GET  /api/entities 当前版本全部解析结论；</li>
 *   <li>GET  /api/entities/{namespace}/{value} 按任意标识符查询；</li>
 *   <li>GET  /api/rejected 当前版本被拒绝/冲突的记录与原因。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api")
public class AggregationController {

    private final AggregationService aggregationService;
    private final SnapshotImporter importer;

    public AggregationController(AggregationService aggregationService, SnapshotImporter importer) {
        this.aggregationService = aggregationService;
        this.importer = importer;
    }

    @PostMapping("/snapshots/import")
    public ImportReport importSnapshot(@RequestBody ImportRequest request) {
        List<SnapshotInput> inputs = request.sources().stream()
                .map(s -> new SnapshotInput(
                        s.source(),
                        SourceFormat.valueOf(s.format().trim().toUpperCase()),
                        s.content()))
                .toList();
        return importer.importSnapshot(inputs);
    }

    @GetMapping("/snapshots/current")
    public Map<String, Object> current() {
        return Map.of(
                "version", aggregationService.currentVersion().orElse("(none)"),
                "rulesVersion", importer.rules().rulesVersion(),
                "entities", aggregationService.listEntities().size());
    }

    @GetMapping("/snapshots/versions")
    public List<SnapshotInfo> versions() {
        return aggregationService.versions();
    }

    @PostMapping("/snapshots/rollback/{version}")
    public ResponseEntity<Map<String, Object>> rollback(@PathVariable String version) {
        boolean ok = aggregationService.rollback(version);
        return ok
                ? ResponseEntity.ok(Map.of("rolledBackTo", version))
                : ResponseEntity.status(404).body(Map.of("error", "未知版本: " + version));
    }

    @GetMapping("/entities")
    public List<ResolvedEntity> entities() {
        return aggregationService.listEntities();
    }

    @GetMapping("/entities/{namespace}/{value}")
    public ResponseEntity<ResolvedEntity> byIdentifier(@PathVariable String namespace,
                                                       @PathVariable String value) {
        return aggregationService.resolveByIdentifier(namespace, value)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/rejected")
    public Object rejected() {
        return aggregationService.rejected();
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleBadRequest(IllegalArgumentException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", ex.getMessage()));
    }

    /** 导入请求体。 */
    public record ImportRequest(List<SourceBody> sources) {
    }

    /** 单个来源请求体。 */
    public record SourceBody(String source, String format, String content) {
    }
}
