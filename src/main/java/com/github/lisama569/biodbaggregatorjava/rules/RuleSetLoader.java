package com.github.lisama569.biodbaggregatorjava.rules;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads rule-set declarations from <b>local</b> JSON files only (classpath locations
 * and/or local directories configured by {@code aggregator.rules.locations}).
 *
 * <p>Loading never falls back to defaults for a bad file: each unreadable, malformed
 * or structurally invalid declaration is returned as a {@link RuleSetLoadFailure}
 * with a distinguishable {@link RuleRejectionReason}. Networking is never used.</p>
 */
@Component
public class RuleSetLoader {

    private static final Logger log = LoggerFactory.getLogger(RuleSetLoader.class);

    private final ObjectMapper objectMapper;
    private final ResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
    private final List<String> locations;

    public RuleSetLoader(ObjectMapper objectMapper,
                         @Value("${aggregator.rules.locations:classpath*:rules/}")
                         List<String> locations) {
        this.objectMapper = objectMapper;
        this.locations = List.copyOf(locations);
    }

    /** Scan every configured location and parse all {@code *.json} rule declarations. */
    public RuleSetLoadResult loadAll() {
        List<RuleSet> loaded = new ArrayList<>();
        List<RuleSetLoadFailure> failures = new ArrayList<>();
        Map<String, List<String>> originsById = new LinkedHashMap<>();
        for (String location : locations) {
            scanLocation(location, loaded, failures, originsById);
        }
        // Cross-file duplicate ids: one id declared by more than one origin file is
        // ambiguous, so both copies are refused with a distinguishable reason and dropped.
        for (Map.Entry<String, List<String>> e : originsById.entrySet()) {
            if (e.getValue().size() > 1) {
                for (String origin : e.getValue()) {
                    failures.add(new RuleSetLoadFailure(origin, e.getKey(),
                            RuleRejectionReason.DUPLICATE_RULE_SET_ID,
                            "rule set id '" + e.getKey() + "' is declared by more than one file: "
                                    + e.getValue()));
                }
            }
        }
        loaded.removeIf(rs -> originsById.getOrDefault(rs.id(), List.of()).size() > 1);
        loaded.sort((a, b) -> a.id().compareTo(b.id()));
        log.info("rule sets scanned: locations={} loaded={} rejected={}",
                locations, loaded.stream().map(RuleSet::id).toList(),
                failures.stream().map(f -> f.origin() + ":" + f.reason()).toList());
        return new RuleSetLoadResult(loaded, failures);
    }

    private void scanLocation(String location,
                              List<RuleSet> loaded,
                              List<RuleSetLoadFailure> failures,
                              Map<String, List<String>> originsById) {
        String pattern = location.endsWith("/") ? location + "*.json" : location + "/*.json";
        List<Resource> resources = new ArrayList<>();
        if (location.startsWith("classpath:") || location.contains("*")) {
            try {
                for (Resource r : resolver.getResources(pattern)) {
                    resources.add(r);
                }
            } catch (Exception e) {
                failures.add(new RuleSetLoadFailure(location, null,
                        RuleRejectionReason.MALFORMED_RULE_DOCUMENT,
                        "cannot scan location: " + e.getMessage()));
                return;
            }
        } else {
            Path dir = Path.of(location);
            if (!Files.isDirectory(dir)) {
                log.info("rules location does not exist or is not a directory, skipped: {}", dir);
                return;
            }
            try (var stream = Files.list(dir)) {
                stream.filter(p -> p.getFileName().toString().endsWith(".json"))
                        .map(p -> p.toFile().toURI().toString())
                        .map(uri -> resolver.getResource(uri))
                        .forEach(resources::add);
            } catch (Exception e) {
                failures.add(new RuleSetLoadFailure(location, null,
                        RuleRejectionReason.MALFORMED_RULE_DOCUMENT,
                        "cannot list directory: " + e.getMessage()));
                return;
            }
        }
        resources.sort((a, b) -> {
            try {
                return a.getURI().toString().compareTo(b.getURI().toString());
            } catch (Exception e) {
                return 0;
            }
        });
        for (Resource resource : resources) {
            parseResource(resource, loaded, failures, originsById);
        }
    }

    private void parseResource(Resource resource,
                               List<RuleSet> loaded,
                               List<RuleSetLoadFailure> failures,
                               Map<String, List<String>> originsById) {
        String origin = resource.getDescription();
        try {
            String text;
            try (InputStream in = resource.getInputStream()) {
                text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            JsonNode root;
            try {
                root = objectMapper.readTree(text);
            } catch (Exception e) {
                failures.add(new RuleSetLoadFailure(origin, null,
                        RuleRejectionReason.MALFORMED_RULE_DOCUMENT,
                        "invalid JSON: " + e.getMessage()));
                return;
            }
            if (root == null || !root.isObject()) {
                failures.add(new RuleSetLoadFailure(origin, null,
                        RuleRejectionReason.MALFORMED_RULE_DOCUMENT,
                        "rule document root must be a JSON object"));
                return;
            }
            String id = root.path("id").asText(null);
            try {
                RuleSet ruleSet = parseRuleSet(root, sha256(text));
                ruleSet.validate();
                loaded.add(ruleSet);
                originsById.computeIfAbsent(ruleSet.id(), k -> new ArrayList<>()).add(origin);
                log.info("rule set accepted: origin={} id={} sources={} priority={} strict={}",
                        origin, ruleSet.id(), ruleSet.sources(),
                        ruleSet.sourcePriority(), ruleSet.strictSources());
            } catch (InvalidRuleSetException e) {
                failures.add(new RuleSetLoadFailure(origin,
                        e.ruleSetId() == null || e.ruleSetId().equals("<null>") ? id : e.ruleSetId(),
                        e.reason(), e.getMessage()));
            }
        } catch (Exception e) {
            failures.add(new RuleSetLoadFailure(origin, null,
                    RuleRejectionReason.MALFORMED_RULE_DOCUMENT,
                    "cannot read rule document: " + e.getMessage()));
        }
    }

    private RuleSet parseRuleSet(JsonNode root, String contentHash) {
        String id = root.path("id").asText(null);
        String description = root.path("description").asText(null);
        List<String> sources = readStringList(root.get("sources"));
        List<String> priority = readStringList(root.get("sourcePriority"));
        boolean strict = root.path("strictSources").asBoolean(false);

        Map<String, FieldRule> fieldRules = new LinkedHashMap<>();
        JsonNode rulesNode = root.get("fieldRules");
        if (rulesNode != null && rulesNode.isObject()) {
            List<String> fieldNames = new ArrayList<>();
            rulesNode.fieldNames().forEachRemaining(fieldNames::add);
            for (String field : fieldNames) {
                JsonNode fr = rulesNode.get(field);
                List<String> frPriority;
                if (fr != null && fr.has("priority")) {
                    frPriority = readStringList(fr.get("priority"));
                } else {
                    frPriority = readStringList(fr);
                }
                fieldRules.put(field, new FieldRule(field, frPriority));
            }
        }
        return new RuleSet(id, description, sources, priority, fieldRules, strict, contentHash);
    }

    private static List<String> readStringList(JsonNode node) {
        List<String> out = new ArrayList<>();
        if (node == null || !node.isArray()) {
            return out;
        }
        for (JsonNode element : node) {
            if (element != null && element.isTextual() && !element.asText().isBlank()) {
                out.add(element.asText().trim());
            }
        }
        return out;
    }

    static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
