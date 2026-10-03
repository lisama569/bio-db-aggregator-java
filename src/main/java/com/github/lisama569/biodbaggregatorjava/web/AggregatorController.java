package com.github.lisama569.biodbaggregatorjava.web;

import com.github.lisama569.biodbaggregatorjava.model.ImportReport;
import com.github.lisama569.biodbaggregatorjava.model.ResolutionComparison;
import com.github.lisama569.biodbaggregatorjava.model.ResolutionReport;
import com.github.lisama569.biodbaggregatorjava.model.ResolvedEntity;
import com.github.lisama569.biodbaggregatorjava.model.Snapshot;
import com.github.lisama569.biodbaggregatorjava.model.SourceFormat;
import com.github.lisama569.biodbaggregatorjava.rules.InvalidRuleSetException;
import com.github.lisama569.biodbaggregatorjava.rules.RuleSet;
import com.github.lisama569.biodbaggregatorjava.rules.UnknownRuleSetException;
import com.github.lisama569.biodbaggregatorjava.service.AggregatorService;
import com.github.lisama569.biodbaggregatorjava.service.DefaultAggregatorService;
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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Local REST surface for snapshot imports, version switching, rule-set management,
 * rule-reproducible resolutions and consistent queries.
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
     * Naming a rule set different from the version's pinned stamp is also a 409
     * ({@code RULE_SET_MISMATCH}) instead of silently changing history.
     */
    @PostMapping(path = "/snapshots/{version}/imports", consumes = "text/plain")
    public ResponseEntity<ImportReport> importSnapshot(
            @PathVariable String version,
            @RequestParam String source,
            @RequestParam SourceFormat format,
            @RequestParam(required = false) String rules,
            @RequestBody(required = false) String body) {
        ImportReport report = service.importSnapshot(version, source, format, body, rules);
        if ("CONFLICTING_REIMPORT".equals(report.status())
                || DefaultAggregatorService.RULE_SET_MISMATCH.equals(report.status())) {
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
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("versions", service.versions());
        body.put("current", service.currentVersion().orElse(null));
        body.put("activeRules", service.activeRuleSet().map(RuleSet::id).orElse(null));
        return body;
    }

    @GetMapping("/snapshots/{version}/snapshot")
    public ResponseEntity<Snapshot> snapshot(@PathVariable String version) {
        return ResponseEntity.of(service.getSnapshot(version));
    }

    /**
     * Resolution of a version. Without {@code rules} the version's <b>pinned</b>, stamped
     * conclusion is returned; with {@code rules} the same data is (re)adjudicated under
     * that rule set, leaving the pinned conclusion untouched.
     */
    @GetMapping("/snapshots/{version}/resolution")
    public ResponseEntity<ResolutionReport> resolution(
            @PathVariable String version,
            @RequestParam(required = false) String rules) {
        if (rules == null || rules.isBlank()) {
            return ResponseEntity.of(service.getResolution(version));
        }
        if (service.getSnapshot(version).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(service.getResolution(version, rules));
    }

    /** Diff the same version under two rule sets. */
    @GetMapping("/snapshots/{version}/resolution/compare")
    public ResponseEntity<ResolutionComparison> compare(
            @PathVariable String version,
            @RequestParam String rulesA,
            @RequestParam String rulesB) {
        if (service.getSnapshot(version).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(service.compareResolutions(version, rulesA, rulesB));
    }

    /**
     * All entities of the current single-version view. {@code rules} optionally selects the
     * rule set; omitted means the current version's pinned rule set.
     */
    @GetMapping("/entities")
    public List<ResolvedEntity> entities(@RequestParam(required = false) String identifier,
                                         @RequestParam(required = false) String rules) {
        if (identifier != null && !identifier.isBlank()) {
            return service.resolveIdentifier(identifier, rules).map(List::of).orElse(List.of());
        }
        return service.queryAll(rules);
    }

    @GetMapping("/entities/{identifier:.+}")
    public ResponseEntity<ResolvedEntity> entity(@PathVariable String identifier,
                                                 @RequestParam(required = false) String rules) {
        return ResponseEntity.of(service.resolveIdentifier(identifier, rules));
    }

    // ---- rule-set management -------------------------------------------------

    @GetMapping("/rules")
    public Map<String, Object> listRules() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("active", service.activeRuleSet().map(RuleSet::id).orElse(null));
        body.put("ruleSets", service.listRuleSets());
        return body;
    }

    /** Switch the active rule set for future publications/current default queries. */
    @PostMapping("/rules/{ruleSetId}/activate")
    public ResponseEntity<Map<String, Object>> activateRules(@PathVariable String ruleSetId) {
        boolean ok = service.activateRuleSet(ruleSetId);
        if (!ok) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("ruleSetId", ruleSetId, "activated", false,
                            "message", "unknown rule set"));
        }
        return ResponseEntity.ok(Map.of("ruleSetId", ruleSetId, "activated", true));
    }

    /** Re-scan local rule-set files; bad declarations are reported, never silently ignored. */
    @PostMapping("/rules/reload")
    public Map<String, Object> reloadRules() {
        var result = service.reloadRuleSets();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("active", result.activeRuleSetId());
        body.put("loadedIds", result.loadedIds());
        body.put("failures", result.failures());
        return body;
    }

    @ExceptionHandler(UnknownRuleSetException.class)
    public ResponseEntity<Map<String, String>> unknownRules(UnknownRuleSetException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", "UNKNOWN_RULE_SET", "ruleSetId", e.ruleSetId(),
                        "message", e.getMessage()));
    }

    @ExceptionHandler(InvalidRuleSetException.class)
    public ResponseEntity<Map<String, String>> invalidRules(InvalidRuleSetException e) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(Map.of("error", "INVALID_RULE_SET", "ruleSetId",
                        e.ruleSetId() == null ? "" : e.ruleSetId(),
                        "reason", e.reason().name(), "message", e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
}
