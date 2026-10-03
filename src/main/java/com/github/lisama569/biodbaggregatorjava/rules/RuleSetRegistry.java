package com.github.lisama569.biodbaggregatorjava.rules;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Process-wide, thread-safe registry of rule sets.
 *
 * <p>The whole catalog ({@code id -> RuleSet}) plus the <b>active</b> pointer are held in
 * one immutable snapshot and published atomically. Readers capturing a rule set keep the
 * exact immutable instance pinned to a published snapshot forever: a later reload that
 * edits, replaces or removes that id cannot rewrite historical conclusions.</p>
 */
@Component
public class RuleSetRegistry {

    private static final Logger log = LoggerFactory.getLogger(RuleSetRegistry.class);

    /** The built-in default declaration shipped under {@code src/main/resources/rules/}. */
    public static final String DEFAULT_RULE_SET_ID = "priority-rules-v1";

    private final RuleSetLoader loader;
    private final String configuredActiveId;
    private final AtomicReference<Catalog> catalogRef = new AtomicReference<>();

    public RuleSetRegistry(
            RuleSetLoader loader,
            @Value("${aggregator.rules.active:}") String configuredActiveId) {
        this.loader = loader;
        this.configuredActiveId = configuredActiveId == null ? "" : configuredActiveId.trim();
    }

    private record Catalog(Map<String, RuleSet> ruleSets, String activeId) {
    }

    @PostConstruct
    public void init() {
        RuleSetReloadResult result = reload();
        if (!result.failures().isEmpty()) {
            throw new IllegalStateException("startup rule-set loading produced "
                    + result.failures().size() + " refusal(s), first: "
                    + result.failures().get(0).detail());
        }
        if (catalogRef.get() == null) {
            throw new IllegalStateException("no rule sets found in configured locations");
        }
    }

    /**
     * Re-scan all locations and atomically publish a new catalog. Bad files are reported
     * and excluded; they never cause a silent fallback. If loading yields no usable rule
     * sets the previous catalog is retained.
     */
    public RuleSetReloadResult reload() {
        RuleSetLoadResult scanned = loader.loadAll();
        synchronized (this) {
            List<RuleSetLoadFailure> failures = new ArrayList<>(scanned.failures());
            Map<String, RuleSet> byId = new LinkedHashMap<>();
            for (RuleSet rs : scanned.loaded()) {
                // The loader has already removed cross-file duplicate ids; put is safe.
                byId.put(rs.id(), rs);
            }

            if (byId.isEmpty()) {
                Catalog current = catalogRef.get();
                if (current != null) {
                    log.warn("reload yielded no valid rule sets; keeping previous catalog {}",
                            current.ruleSets().keySet());
                    return new RuleSetReloadResult(current.activeId(),
                            current.ruleSets().keySet().stream().sorted().toList(), failures);
                }
                return new RuleSetReloadResult(null, List.of(), failures);
            }

            Catalog previous = catalogRef.get();
            String activeId = chooseActiveId(previous == null ? null : previous.activeId(),
                    byId);
            catalogRef.set(new Catalog(Map.copyOf(byId), activeId));
            log.info("rule catalog published: ids={} active={} refusals={}",
                    byId.keySet(), activeId, failures.size());
            return new RuleSetReloadResult(activeId,
                    byId.keySet().stream().sorted().toList(), failures);
        }
    }

    private String chooseActiveId(String previousActive, Map<String, RuleSet> byId) {
        if (!configuredActiveId.isEmpty()) {
            if (!byId.containsKey(configuredActiveId)) {
                throw new IllegalStateException("configured active rule set '"
                        + configuredActiveId + "' not found among loaded rule sets " + byId.keySet());
            }
            return configuredActiveId;
        }
        if (previousActive != null && byId.containsKey(previousActive)) {
            return previousActive;
        }
        if (byId.containsKey(DEFAULT_RULE_SET_ID)) {
            return DEFAULT_RULE_SET_ID;
        }
        return byId.keySet().stream().sorted().findFirst().orElseThrow();
    }

    /** Atomically point the active rule set at a loaded rule set. */
    public synchronized boolean activate(String id) {
        Catalog current = catalogRef.get();
        if (current == null || !current.ruleSets().containsKey(id)) {
            return false;
        }
        if (!id.equals(current.activeId())) {
            catalogRef.set(new Catalog(current.ruleSets(), id));
            log.info("active rule set switched: {} -> {}", current.activeId(), id);
        }
        return true;
    }

    public Optional<RuleSet> active() {
        Catalog current = catalogRef.get();
        return current == null ? Optional.empty() : Optional.ofNullable(current.ruleSets().get(current.activeId()));
    }

    public RuleSet requireActive() {
        return active().orElseThrow(() -> new IllegalStateException("no active rule set"));
    }

    public Optional<RuleSet> find(String id) {
        Catalog current = catalogRef.get();
        return current == null ? Optional.empty() : Optional.ofNullable(current.ruleSets().get(id));
    }

    /** Resolve by explicit id, or return the active rule set when {@code idOrNull} is null/blank. */
    public RuleSet resolve(String idOrNull) {
        if (idOrNull == null || idOrNull.isBlank()) {
            return requireActive();
        }
        return find(idOrNull).orElseThrow(() -> new UnknownRuleSetException(idOrNull));
    }

    public List<RuleSet> all() {
        Catalog current = catalogRef.get();
        if (current == null) {
            return List.of();
        }
        return current.ruleSets().values().stream()
                .sorted((a, b) -> a.id().compareTo(b.id())).toList();
    }
}
