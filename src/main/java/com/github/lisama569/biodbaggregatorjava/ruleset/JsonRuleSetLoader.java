package com.github.lisama569.biodbaggregatorjava.ruleset;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Loads {@code *.json} rule-set definitions from a classpath directory and zero or more
 * local filesystem directories. Purely local: no network, and rule content is never
 * hard-coded here.
 *
 * <p>Every definition is validated eagerly. A malformed file is rejected with a
 * distinguishable {@link RuleSetFailureReason}; callers must not fall back to defaults on
 * behalf of a rejected definition.</p>
 */
@Component
public class JsonRuleSetLoader implements RuleSetLoader {

    private static final Logger log = LoggerFactory.getLogger(JsonRuleSetLoader.class);
    private static final Set<String> CANONICAL_FIELDS = Set.of("symbol", "name", "sequence", "organism");

    private final ObjectMapper mapper = new ObjectMapper();
    private final PathMatchingResourcePatternResolver resolver =
            new PathMatchingResourcePatternResolver();
    private final String classpathDir;
    private final List<Path> externalDirs;

    public JsonRuleSetLoader(
            @Value("${aggregator.ruleset.classpath-dir:rulesets}") String classpathDir,
            @Value("${aggregator.ruleset.dirs:}") String externalDirsCsv) {
        this.classpathDir = classpathDir == null ? "rulesets" : classpathDir.trim();
        List<Path> dirs = new ArrayList<>();
        if (externalDirsCsv != null) {
            for (String part : externalDirsCsv.split(",")) {
                String t = part.trim();
                if (!t.isEmpty()) {
                    dirs.add(Path.of(t));
                }
            }
        }
        this.externalDirs = List.copyOf(dirs);
    }

    @Override
    public RuleSet load(String origin, byte[] raw) {
        if (raw == null || raw.length == 0) {
            throw reject(origin, null, RuleSetFailureReason.INVALID_SYNTAX,
                    "rule set definition is empty");
        }
        JsonNode root;
        try {
            root = mapper.readTree(raw);
        } catch (JsonProcessingException e) {
            throw reject(origin, null, RuleSetFailureReason.INVALID_SYNTAX,
                    "not valid JSON: " + e.getOriginalMessage());
        } catch (IOException e) {
            throw reject(origin, null, RuleSetFailureReason.INVALID_SYNTAX,
                    "unreadable rule set definition: " + e.getMessage());
        }
        if (root == null || !root.isObject()) {
            throw reject(origin, null, RuleSetFailureReason.INVALID_SYNTAX,
                    "top-level rule set definition must be a JSON object");
        }

        String id = root.path("id").asText("").trim();
        if (id.isEmpty()) {
            throw reject(origin, null, RuleSetFailureReason.BLANK_ID,
                    "rule set 'id' must be a non-blank string");
        }
        String description = root.path("description").asText("");

        JsonNode priorityNode = root.get("sourcePriority");
        if (priorityNode == null || !priorityNode.isArray() || priorityNode.isEmpty()) {
            throw reject(id, id, RuleSetFailureReason.EMPTY_PRIORITY,
                    "sourcePriority must be a non-empty array of source names");
        }
        List<String> sourcePriority = new ArrayList<>();
        for (JsonNode item : priorityNode) {
            if (!item.isTextual() || item.asText().isBlank()) {
                throw reject(id, id, RuleSetFailureReason.INVALID_SYNTAX,
                        "sourcePriority entries must be non-blank strings");
            }
            sourcePriority.add(item.asText().trim());
        }
        requireNoDuplicates(id, "sourcePriority", sourcePriority);

        Map<String, FieldRule> fields = new LinkedHashMap<>();
        JsonNode fieldsNode = root.get("fields");
        if (fieldsNode != null) {
            if (!fieldsNode.isObject()) {
                throw reject(id, id, RuleSetFailureReason.INVALID_SYNTAX,
                        "'fields' must be an object keyed by canonical field name");
            }
            List<String> fieldNames = new ArrayList<>();
            fieldsNode.fieldNames().forEachRemaining(fieldNames::add);
            fieldNames.sort(String::compareTo);
            for (String fieldName : fieldNames) {
                if (!CANONICAL_FIELDS.contains(fieldName)) {
                    throw reject(id, id, RuleSetFailureReason.UNKNOWN_FIELD,
                            "field override '" + fieldName + "' is not a canonical field "
                                    + CANONICAL_FIELDS);
                }
                JsonNode ruleNode = fieldsNode.get(fieldName);
                if (!ruleNode.isObject()) {
                    throw reject(id, id, RuleSetFailureReason.INVALID_SYNTAX,
                            "fields." + fieldName + " must be an object");
                }
                String strategyText = ruleNode.path("strategy").asText("SOURCE_PRIORITY").trim();
                FieldResolutionStrategy strategy;
                try {
                    strategy = FieldResolutionStrategy.valueOf(strategyText);
                } catch (IllegalArgumentException e) {
                    throw reject(id, id, RuleSetFailureReason.UNKNOWN_STRATEGY,
                            "fields." + fieldName + ".strategy='" + strategyText
                                    + "' is unknown; allowed: "
                                    + java.util.List.of(FieldResolutionStrategy.values()));
                }
                List<String> override = new ArrayList<>();
                JsonNode overrideNode = ruleNode.get("priority");
                if (overrideNode != null) {
                    if (!overrideNode.isArray()) {
                        throw reject(id, id, RuleSetFailureReason.INVALID_SYNTAX,
                                "fields." + fieldName + ".priority must be an array");
                    }
                    for (JsonNode item : overrideNode) {
                        if (!item.isTextual() || item.asText().isBlank()) {
                            throw reject(id, id, RuleSetFailureReason.INVALID_SYNTAX,
                                    "fields." + fieldName + ".priority entries must be strings");
                        }
                        override.add(item.asText().trim());
                    }
                }
                requireNoDuplicates(id, "fields." + fieldName + ".priority", override);
                for (String src : override) {
                    if (!sourcePriority.contains(src)) {
                        throw reject(id, id, RuleSetFailureReason.OVERRIDE_SOURCE_NOT_DECLARED,
                                "fields." + fieldName + ".priority references source '" + src
                                        + "' which is not declared in global sourcePriority "
                                        + sourcePriority);
                    }
                }
                fields.put(fieldName, new FieldRule(strategy, override));
            }
        }

        String hash = sha256Hex(raw);
        log.info("rule set loaded: origin={} id={} sourcePriority={} fieldOverrides={} hash={}",
                origin, id, sourcePriority, fields.keySet(), hash);
        return new RuleSet(id, description, sourcePriority, fields, hash);
    }

    private static void requireNoDuplicates(String ruleSetId, String what, List<String> values) {
        List<String> seen = new ArrayList<>();
        for (String v : values) {
            if (seen.contains(v)) {
                throw reject(ruleSetId, ruleSetId, RuleSetFailureReason.DUPLICATE_PRIORITY_SOURCE,
                        what + " lists source '" + v + "' more than once: " + values);
            }
            seen.add(v);
        }
    }

    @Override
    public List<RuleSet> loadAll() {
        // Deterministic origin order so equal content always loads the same way.
        Map<String, byte[]> files = new TreeMap<>();
        String pattern = "classpath*:" + classpathDir + "/*.json";
        try {
            for (Resource resource : resolver.getResources(pattern)) {
                if (resource.isReadable()) {
                    String origin = resource.getURI().toString();
                    files.put(origin, resource.getContentAsByteArray());
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("failed scanning classpath " + pattern, e);
        }
        for (Path dir : externalDirs) {
            if (!Files.isDirectory(dir)) {
                log.warn("rule set external dir does not exist, skipping: {}", dir);
                continue;
            }
            List<Path> jsonFiles;
            try (var stream = Files.list(dir)) {
                jsonFiles = stream.filter(p -> p.getFileName().toString().endsWith(".json"))
                        .sorted().toList();
            } catch (IOException e) {
                throw new IllegalStateException("failed listing rule set dir " + dir, e);
            }
            for (Path p : jsonFiles) {
                try {
                    files.put(p.toUri().toString(), Files.readAllBytes(p));
                } catch (IOException e) {
                    throw new IllegalStateException("failed reading rule set file " + p, e);
                }
            }
        }
        List<RuleSet> ruleSets = new ArrayList<>();
        files.forEach((origin, bytes) -> ruleSets.add(load(origin, bytes)));
        log.info("rule set scan complete: locations={} externalDirs={} loaded={}",
                pattern, externalDirs, ruleSets.size());
        return List.copyOf(ruleSets);
    }

    private static RuleSetException reject(String origin, String id,
                                           RuleSetFailureReason reason, String message) {
        return new RuleSetException(reason, id, "rejected rule set " + origin + ": " + message);
    }

    static String sha256Hex(byte[] input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(input));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
