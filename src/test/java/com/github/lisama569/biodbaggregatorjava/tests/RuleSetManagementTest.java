package com.github.lisama569.biodbaggregatorjava.tests;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.lisama569.biodbaggregatorjava.rules.InvalidRuleSetException;
import com.github.lisama569.biodbaggregatorjava.rules.RuleRejectionReason;
import com.github.lisama569.biodbaggregatorjava.rules.RuleSet;
import com.github.lisama569.biodbaggregatorjava.rules.RuleSetLoader;
import com.github.lisama569.biodbaggregatorjava.rules.RuleSetLoadResult;
import com.github.lisama569.biodbaggregatorjava.rules.RuleSetRegistry;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Rule-set declaration/loading/validation tests. Every refusal must carry a
 * distinguishable reason; bad rules must never silently fall back to defaults.
 */
class RuleSetManagementTest {

    private static final Logger log = LoggerFactory.getLogger(RuleSetManagementTest.class);

    private Path badDir() {
        return Path.of("src/test/resources/bad-rules").toAbsolutePath();
    }

    private Path goodDir() {
        return Path.of("src/test/resources/rules").toAbsolutePath();
    }

    private Path shippedDir() {
        return Path.of("src/main/resources/rules").toAbsolutePath();
    }

    /** Both shipped and test rule directories, mirroring the test classpath union. */
    private RuleSetLoader allGoodLoader() {
        return new RuleSetLoader(new ObjectMapper(),
                List.of(shippedDir().toString(), goodDir().toString()));
    }

    private RuleSetLoader loaderFor(String dir) {
        return new RuleSetLoader(new ObjectMapper(), List.of(dir.toString()));
    }

    private Map<RuleRejectionReason, List<String>> refusalIndex(RuleSetLoadResult result) {
        return result.failures().stream()
                .collect(Collectors.groupingBy(
                        f -> f.reason(),
                        Collectors.mapping(f -> f.origin() + "/" + f.ruleSetId(),
                                Collectors.toList())));
    }

    @Test
    void goodRuleSetsLoadFromLocalFiles() {
        RuleSetLoadResult result = allGoodLoader().loadAll();
        log.info("good dir load: loaded={} failures={}",
                result.loaded().stream().map(r -> r.id() + ":" + r.sourcePriority()).toList(),
                result.failures());
        assertTrue(result.failures().isEmpty(), "shipped/test rule files must all be valid: "
                + result.failures());

        Map<String, RuleSet> byId = result.loaded().stream()
                .collect(Collectors.toMap(RuleSet::id, Function.identity()));
        RuleSet ensemblRules = byId.get("priority-rules-ensembl-v1");
        assertNotNull(ensemblRules);
        assertEquals(List.of("ensembl", "uniprot", "refseq", "genbank", "ncbi"),
                ensemblRules.sourcePriority());
        // field-level rule overrides global priority for name only
        assertEquals(List.of("ensembl", "uniprot", "refseq", "genbank", "ncbi"),
                ensemblRules.priorityFor("name"));
        assertEquals(List.of("ensembl", "uniprot", "refseq", "genbank", "ncbi"),
                ensemblRules.priorityFor("sequence"));
        assertNotNull(ensemblRules.contentHash(), "declaration content is hashed for pinning");
    }

    @Test
    void badRuleFilesAreRefusedWithDistinguishableReasons() {
        RuleSetLoadResult result = loaderFor(badDir().toString()).loadAll();
        Map<RuleRejectionReason, List<String>> byReason = refusalIndex(result);
        log.info("bad dir refusals: {}", byReason);

        // every bad declaration refused; nothing silently loaded
        assertTrue(result.loaded().stream().noneMatch(r -> r.id().startsWith("bad-")),
                "bad rule sets must not be loaded");
        assertTrue(byReason.containsKey(RuleRejectionReason.DUPLICATE_SOURCE_IN_PRIORITY),
                "duplicate source in priority must be distinguishable: " + byReason.keySet());
        assertTrue(byReason.containsKey(RuleRejectionReason.UNKNOWN_SOURCE_REFERENCED),
                "priority/field referencing undeclared source must be distinguishable");
        assertTrue(byReason.containsKey(RuleRejectionReason.MISSING_SOURCE_PRIORITY),
                "empty priority must be distinguishable");
        assertTrue(byReason.containsKey(RuleRejectionReason.UNKNOWN_FIELD_REFERENCED),
                "unknown adjudicated field must be distinguishable");
        assertTrue(byReason.containsKey(RuleRejectionReason.MALFORMED_RULE_DOCUMENT),
                "syntactically broken JSON must be distinguishable");
        assertTrue(byReason.containsKey(RuleRejectionReason.MISSING_RULE_SET_ID),
                "missing id must be distinguishable");
        assertTrue(byReason.containsKey(RuleRejectionReason.DUPLICATE_RULE_SET_ID),
                "same id across two files must be distinguishable");
        assertEquals(9, result.failures().size(),
                "seven bad-* files + two duplicated-id files = 9 refusals");
    }

    @Test
    void validateRejectsDuplicatePrioritySourceDirectly() {
        RuleSet rs = new RuleSet("x", null, List.of("a", "b"), List.of("a", "b", "a"),
                Map.of(), false, null);
        InvalidRuleSetException ex = assertThrows(InvalidRuleSetException.class, rs::validate);
        assertEquals(RuleRejectionReason.DUPLICATE_SOURCE_IN_PRIORITY, ex.reason());
        log.info("direct validation refused: id={} reason={}", ex.ruleSetId(), ex.reason());
    }

    @Test
    void validateRejectsReferenceToUnknownSourceDirectly() {
        RuleSet rs = new RuleSet("x", null, List.of("a"), List.of("a", "b"),
                Map.of(), false, null);
        InvalidRuleSetException ex = assertThrows(InvalidRuleSetException.class, rs::validate);
        assertEquals(RuleRejectionReason.UNKNOWN_SOURCE_REFERENCED, ex.reason());
    }

    @Test
    void registryActivatesAndKeepsPinnedImmutableInstancesAcrossReload() throws Exception {
        // Registry over a directory containing only good test rule sets.
        RuleSetRegistry registry = new RuleSetRegistry(
                allGoodLoader(), "");
        registry.init();
        // default shipped id wins active selection
        assertEquals("priority-rules-v1", registry.active().orElseThrow().id());

        RuleSet firstV1 = registry.requireActive();
        assertTrue(registry.activate("priority-rules-ensembl-v1"));
        assertEquals("priority-rules-ensembl-v1", registry.active().orElseThrow().id());

        // switching to an unknown rule set is refused, not defaulted
        assertFalse(registry.activate("does-not-exist"));
        assertEquals("priority-rules-ensembl-v1", registry.active().orElseThrow().id());
        assertThrows(com.github.lisama569.biodbaggregatorjava.rules.UnknownRuleSetException.class,
                () -> registry.resolve("does-not-exist"));

        // After switching active, the previously captured instance stays available by
        // identity (simulates a snapshot pinning it forever).
        RuleSet pinnedForever = firstV1;
        registry.reload();
        assertSame(pinnedForever, firstV1,
                "a pinned rule set instance is immutable and unaffected by catalog reload");
        assertEquals(Optional.of(firstV1.id()),
                registry.find(firstV1.id()).map(RuleSet::id));
        log.info("registry test: active now={} pinned remains id={}",
                registry.active().orElseThrow().id(), pinnedForever.id());
    }

    @Test
    void startupFailsLoudWhenShippedRulesBroken() {
        RuleSetRegistry registry = new RuleSetRegistry(
                loaderFor(badDir().toString()), "");
        IllegalStateException ex = assertThrows(IllegalStateException.class, registry::init);
        log.info("startup with broken catalog refused as expected: {}", ex.getMessage());
        assertNotNull(ex.getMessage());
    }
}
