package com.github.lisama569.biodbaggregatorjava.tests;

import com.github.lisama569.biodbaggregatorjava.ruleset.DefaultRuleSetRegistry;
import com.github.lisama569.biodbaggregatorjava.ruleset.FieldResolutionStrategy;
import com.github.lisama569.biodbaggregatorjava.ruleset.JsonRuleSetLoader;
import com.github.lisama569.biodbaggregatorjava.ruleset.RuleSet;
import com.github.lisama569.biodbaggregatorjava.ruleset.RuleSetException;
import com.github.lisama569.biodbaggregatorjava.ruleset.RuleSetFailureReason;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bad rule sets must be rejected with distinguishable reason codes — never silently
 * ignored or replaced by defaults. Also covers content-addressed registry semantics.
 */
class RuleSetLoaderTest {

    private static final Logger log = LoggerFactory.getLogger(RuleSetLoaderTest.class);

    private final JsonRuleSetLoader loader = new JsonRuleSetLoader("rulesets", "");

    private byte[] bad(String file) throws Exception {
        try (var in = getClass().getResourceAsStream("/bad-rulesets/" + file)) {
            assertTrue(in != null, "missing fixture " + file);
            return in.readAllBytes();
        }
    }

    private RuleSetFailureReason rejectReason(String file) throws Exception {
        byte[] raw = bad(file);
        log.info("loading bad rule set {} ({} bytes):\n{}", file, raw.length,
                new String(raw, StandardCharsets.UTF_8).strip());
        RuleSetException ex = assertThrows(RuleSetException.class,
                () -> loader.load(file, raw));
        log.info("rejected {} with reason={} message={}", file, ex.reason(), ex.getMessage());
        return ex.reason();
    }

    @Test
    void badRuleSetsAreRejectedWithDistinctReasons() throws Exception {
        assertEquals(RuleSetFailureReason.INVALID_SYNTAX, rejectReason("syntax.json"));
        assertEquals(RuleSetFailureReason.BLANK_ID, rejectReason("blank-id.json"));
        assertEquals(RuleSetFailureReason.EMPTY_PRIORITY, rejectReason("empty-priority.json"));
        assertEquals(RuleSetFailureReason.DUPLICATE_PRIORITY_SOURCE, rejectReason("dup-source.json"));
        assertEquals(RuleSetFailureReason.UNKNOWN_STRATEGY, rejectReason("unknown-strategy.json"));
        assertEquals(RuleSetFailureReason.UNKNOWN_FIELD, rejectReason("unknown-field.json"));
        assertEquals(RuleSetFailureReason.OVERRIDE_SOURCE_NOT_DECLARED,
                rejectReason("undeclared-source.json"));
    }

    @Test
    void validRuleSetParsesPriorityFieldsAndContentHash() {
        String json = """
                {"id":"r-x","description":"d","sourcePriority":["uniprot","refseq"],
                 "fields":{"name":{"strategy":"PREFER_LONGEST","priority":["refseq","uniprot"]}}}
                """;
        RuleSet rs = loader.load("inline", json.getBytes(StandardCharsets.UTF_8));
        assertEquals("r-x", rs.id());
        assertEquals(java.util.List.of("uniprot", "refseq"), rs.sourcePriority());
        assertEquals(FieldResolutionStrategy.PREFER_LONGEST,
                rs.fieldRule("name").strategy());
        // undeclared fields fall back to the global default rule
        assertEquals(FieldResolutionStrategy.SOURCE_PRIORITY,
                rs.fieldRule("symbol").strategy());
        assertEquals(64, rs.contentHash().length(), "content hash is sha-256 hex");
        log.info("valid rule parsed: id={} priority={} fields={} hash={}",
                rs.id(), rs.sourcePriority(),
                rs.fields().keySet(), rs.contentHash());
    }

    @Test
    void registryRejectsSameIdWithDifferentContentAndStaysDefault() {
        DefaultRuleSetRegistry registry = new DefaultRuleSetRegistry(loader, "priority-rules-v1");
        // Load from the actual classpath locations (main + test rule sets coexist).
        loader.loadAll().forEach(registry::register);

        RuleSet def = registry.get("priority-rules-v1").orElseThrow();
        RuleSet tampered = new RuleSet("priority-rules-v1", "tampered",
                java.util.List.of("ensembl"), Map.of(), "different-hash");
        RuleSetException ex = assertThrows(RuleSetException.class,
                () -> registry.register(tampered));
        assertEquals(RuleSetFailureReason.DUPLICATE_RULESET_ID, ex.reason());
        // original content remains authoritative
        assertEquals(def.contentHash(),
                registry.get("priority-rules-v1").orElseThrow().contentHash());

        // both shipped rule sets coexist
        assertTrue(registry.get("ensembl-first-v1").isPresent());
        assertTrue(registry.get("test-alt-v1").isPresent());

        // unknown rule set can neither be selected nor resolved
        assertEquals(false, registry.switchCurrent("does-not-exist"));
        RuleSetException unknown = assertThrows(RuleSetException.class,
                () -> registry.require("does-not-exist"));
        assertEquals(RuleSetFailureReason.UNKNOWN_RULESET, unknown.reason());
        log.info("registry immutability verified: ids={}",
                registry.all().stream().map(RuleSet::id).toList());
    }
}
