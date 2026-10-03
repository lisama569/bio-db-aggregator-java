package com.github.lisama569.biodbaggregatorjava.ruleset;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * In-memory, concurrency-safe catalog of validated rule sets.
 *
 * <p>Rule sets are loaded once at startup from the local locations configured for
 * {@link JsonRuleSetLoader} and may also be registered at runtime (e.g. by tests or a
 * local admin endpoint). Registration is content-addressed: the same id with identical
 * content is idempotent, the same id with different content is rejected
 * ({@link RuleSetFailureReason#DUPLICATE_RULESET_ID}) — a rule set is never silently
 * replaced in place, so conclusions stamped with its id stay reproducible.</p>
 *
 * <p>The "current" id lives in an {@link AtomicReference}; switching it is one atomic
 * publication. Reads capture the id (or the resolved {@link RuleSet}) once, so in-flight
 * reads never run half under old and half under new rules.</p>
 */
@Component
public class DefaultRuleSetRegistry implements RuleSetRegistry {

    private static final Logger log = LoggerFactory.getLogger(DefaultRuleSetRegistry.class);

    private final RuleSetLoader loader;
    private final String defaultId;
    private final Map<String, RuleSet> ruleSets = new ConcurrentHashMap<>();
    private final AtomicReference<String> currentId = new AtomicReference<>();

    public DefaultRuleSetRegistry(
            RuleSetLoader loader,
            @Value("${aggregator.ruleset.default-id:priority-rules-v1}") String defaultId) {
        this.loader = loader;
        this.defaultId = defaultId == null || defaultId.isBlank() ? "priority-rules-v1" : defaultId.trim();
    }

    @PostConstruct
    void loadInitial() {
        List<RuleSet> loaded = loader.loadAll();
        for (RuleSet ruleSet : loaded) {
            register(ruleSet);
        }
        RuleSet def = ruleSets.get(defaultId);
        if (def == null) {
            throw new RuleSetException(RuleSetFailureReason.DEFAULT_RULESET_NOT_FOUND, defaultId,
                    "configured default rule set '" + defaultId + "' was not loaded; available="
                            + ruleSets.keySet().stream().sorted().toList());
        }
        currentId.set(defaultId);
        log.info("rule set registry initialized: count={} current={}", ruleSets.size(), defaultId);
    }

    @Override
    public void register(RuleSet ruleSet) {
        RuleSet existing = ruleSets.putIfAbsent(ruleSet.id(), ruleSet);
        if (existing != null && !existing.contentHash().equals(ruleSet.contentHash())) {
            throw new RuleSetException(RuleSetFailureReason.DUPLICATE_RULESET_ID, ruleSet.id(),
                    "rule set '" + ruleSet.id() + "' already registered with different content "
                            + "(existingHash=" + existing.contentHash()
                            + ", newHash=" + ruleSet.contentHash()
                            + "); change the id instead of replacing rules in place");
        }
    }

    @Override
    public Optional<RuleSet> get(String ruleSetId) {
        return Optional.ofNullable(ruleSets.get(ruleSetId));
    }

    /** Like {@link #get(String)} but fails with a distinguishable reason when unknown. */
    public RuleSet require(String ruleSetId) {
        RuleSet ruleSet = ruleSets.get(ruleSetId);
        if (ruleSet == null) {
            throw new RuleSetException(RuleSetFailureReason.UNKNOWN_RULESET, ruleSetId,
                    "unknown rule set '" + ruleSetId + "'; registered="
                            + ruleSets.keySet().stream().sorted().toList());
        }
        return ruleSet;
    }

    @Override
    public List<RuleSet> all() {
        return ruleSets.values().stream()
                .sorted(Comparator.comparing(RuleSet::id))
                .toList();
    }

    @Override
    public RuleSet current() {
        String id = currentId.get();
        if (id == null) {
            throw new IllegalStateException("rule set registry not initialized");
        }
        return require(id);
    }

    @Override
    public String currentId() {
        return currentId.get();
    }

    @Override
    public boolean switchCurrent(String ruleSetId) {
        if (!ruleSets.containsKey(ruleSetId)) {
            log.warn("rule switch rejected: unknown rulesId={}", ruleSetId);
            return false;
        }
        String previous = currentId.getAndSet(ruleSetId);
        log.info("rule set switched: {} -> {}", previous, ruleSetId);
        return true;
    }
}
