package com.github.lisama569.biodbaggregatorjava.web;

import com.github.lisama569.biodbaggregatorjava.model.ImportReport;
import com.github.lisama569.biodbaggregatorjava.model.ResolutionReport;
import com.github.lisama569.biodbaggregatorjava.model.ResolvedEntity;
import com.github.lisama569.biodbaggregatorjava.model.Snapshot;
import com.github.lisama569.biodbaggregatorjava.model.SourceFormat;
import com.github.lisama569.biodbaggregatorjava.service.AggregatorService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Local REST surface for snapshot imports, version switching and consistent queries.
 */
@RestController
@RequestMapping("/api")
public class AggregatorController {

    private final AggregatorService service;

    public AggregatorController(AggregatorService service) {
        this.service = service;
    }

    /**
     * Import one source export into a snapshot version.
     * Re-importing identical content is idempotent (200, {@code duplicateImport=true}).
     * Different content for an existing (version, source) is rejected with 409.
     */
    @PostMapping(path = "/snapshots/{version}/imports", consumes = "text/plain")
    public ResponseEntity<ImportReport> importSnapshot(
            @PathVariable String version,
            @RequestParam String source,
            @RequestParam SourceFormat format,
            @RequestBody(required = false) String body) {
        ImportReport report = service.importSnapshot(version, source, format, body);
        if ("CONFLICTING_REIMPORT".equals(report.status())) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(report);
        }
        return ResponseEntity.ok(report);
    }

    /** Atomically point current view at a previously published version. */
    @PostMapping("/snapshots/{version}/switch")
    public ResponseEntity<Map<String, Object>> switchVersion(@PathVariable String version) {
        boolean ok = service.switchVersion(version);
        if (!ok) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("version", version, "switched", false,
                            "message", "unknown snapshot version"));
        }
        return ResponseEntity.ok(Map.of("version", version, "switched", true));
    }

    @GetMapping("/snapshots")
    public Map<String, Object> listVersions() {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("versions", service.versions());
        body.put("current", service.currentVersion().orElse(null));
        return body;
    }

    @GetMapping("/snapshots/{version}/snapshot")
    public ResponseEntity<Snapshot> snapshot(@PathVariable String version) {
        return ResponseEntity.of(service.getSnapshot(version));
    }

    @GetMapping("/snapshots/{version}/resolution")
    public ResponseEntity<ResolutionReport> resolution(@PathVariable String version) {
        return ResponseEntity.of(service.getResolution(version));
    }

    /** All entities of the current, single-version view. */
    @GetMapping("/entities")
    public List<ResolvedEntity> entities(@RequestParam(required = false) String identifier) {
        if (identifier != null && !identifier.isBlank()) {
            return service.resolveIdentifier(identifier).map(List::of).orElse(List.of());
        }
        return service.queryAll();
    }

    @GetMapping("/entities/{identifier:.+}")
    public ResponseEntity<ResolvedEntity> entity(@PathVariable String identifier) {
        return ResponseEntity.of(service.resolveIdentifier(identifier));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
}
