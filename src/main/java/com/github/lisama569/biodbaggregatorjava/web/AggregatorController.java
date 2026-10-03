package com.github.lisama569.biodbaggregatorjava.web;

import com.github.lisama569.biodbaggregatorjava.model.ImportReport;
import com.github.lisama569.biodbaggregatorjava.model.ResolutionReport;
import com.github.lisama569.biodbaggregatorjava.model.ResolvedEntity;
import com.github.lisama569.biodbaggregatorjava.model.Snapshot;
import com.github.lisama569.biodbaggregatorjava.model.SourceFormat;
import com.github.lisama569.biodbaggregatorjava.model.rules.ResolutionComparison;
import com.github.lisama569.biodbaggregatorjava.ruleset.RuleSet;
import com.github.lisama569.biodbaggregatorjava.ruleset.RuleSetException;
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
    public ResponseEntity<ResolutionReport> resolution(
            @PathVariable String version,
            @RequestParam(required = false) String rules) {
        return ResponseEntity.of(
                rules == null || rules.isBlank()
                        ? service.getResolution(version)
                        : service.getResolution(version, rules));
    }

    /** The rule set id the version's historical conclusion was pinned to at publish time. */
    @GetMapping("/snapshots/{version}/pinned-rules")
    public Map<String, Object> pinnedRules(@PathVariable String version) {
        return service.getResolution(version)
                .map(r -> java.util.Map.<String, Object>of(
                        "version", version, "pinnedRules", r.rulesVersion()))
                .orElseGet(() -> Map.of("version", version, "pinnedRules", ""));
    }

    /** Compare one data snapshot's conclusions under two rule sets. */
    @GetMapping("/snapshots/{version}/rule-comparison")
    public ResolutionComparison compare(
            @PathVariable String version,
            @RequestParam String rulesA,
            @RequestParam String rulesB) {
        return service.compareRules(version, rulesA, rulesB);
    }

    /** All registered rule sets plus the currently selected id. */
    @GetMapping("/rules")
    public Map<String, Object> listRules() {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("current", service.currentRulesId());
        body.put("ruleSets", service.ruleSets().stream()
                .map(r -> {
                    Map<String, Object> view = new java.util.LinkedHashMap<>();
                    view.put("id", r.id());
                    view.put("description", r.description());
                    view.put("sourcePriority", r.sourcePriority());
                    view.put("fields", r.fields());
                    view.put("contentHash", r.contentHash());
                    return view;
                }).toList());
        return body;
    }

    /** Atomically select the rule set used for unqualified reads/new version pins. */
    @PostMapping("/rules/{rulesId}/switch")
    public ResponseEntity<Map<String, Object>> switchRules(@PathVariable String rulesId) {
        boolean ok = service.switchRules(rulesId);
        if (!ok) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("rulesId", rulesId, "switched", false,
                            "message", "unknown rule set"));
        }
        return ResponseEntity.ok(Map.of("rulesId", rulesId, "switched", true,
                "current", service.currentRulesId()));
    }

    /**
     * Entities of the current data version. {@code rules} selects which rule set's
     * conclusion to read; without it the pinned historical conclusion is returned.
     */
    @GetMapping("/entities")
    public List<ResolvedEntity> entities(@RequestParam(required = false) String identifier,
                                         @RequestParam(required = false) String rules) {
        String version = service.currentVersion().orElse(null);
        if (identifier != null && !identifier.isBlank()) {
            if (rules != null && !rules.isBlank() && version != null) {
                return service.resolveIdentifier(version, rules, identifier)
                        .map(List::of).orElse(List.of());
            }
            return service.resolveIdentifier(identifier).map(List::of).orElse(List.of());
        }
        if (rules != null && !rules.isBlank()) {
            return service.queryAll(rules);
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

    /** Bad/unknown rule sets are rejected explicitly with a distinguishable reason code. */
    @ExceptionHandler(RuleSetException.class)
    public ResponseEntity<Map<String, String>> ruleSetRejected(RuleSetException e) {
        HttpStatus status = e.reason()
                == com.github.lisama569.biodbaggregatorjava.ruleset.RuleSetFailureReason.UNKNOWN_RULESET
                ? HttpStatus.NOT_FOUND : HttpStatus.BAD_REQUEST;
        Map<String, String> body = new java.util.LinkedHashMap<>();
        body.put("error", e.getMessage());
        body.put("reason", e.reason().name());
        body.put("rulesId", e.ruleSetId() == null ? "" : e.ruleSetId());
        return ResponseEntity.status(status).body(body);
    }
}
