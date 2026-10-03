package com.github.lisama569.biodbaggregatorjava.rules;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An immutable, named set of resolution rules: source priority plus optional
 * per-field adjudication overrides.
 *
 * <p>Rule sets are declared in local JSON resources (never hard-coded into
 * resolution logic) and stamped onto every published snapshot and every
 * resolution report, so historical conclusions remain reproducible after the
 * active rule set changes.</p>
 *
 * @param id              unique, distinguishing identifier (also used as the rules stamp)
 * @param description     optional human readable description
 * @param sources         sources this rule set knows about
 * @param sourcePriority  adjudication order, highest first; must be a permutation of sources
 * @param fieldRules      optional per-field priority overrides
 * @param strictSources   if true, data from a source not declared in {@code sources}
 *                        is refused at resolution time rather than appended lexicographically
 * @param contentHash     SHA-256 of the declaration text, pinning the exact contents
 */
public record RuleSet(
        String id,
        String description,
        List<String> sources,
        List<String> sourcePriority,
        Map<String, FieldRule> fieldRules,
        boolean strictSources,
        String contentHash
) {

    public RuleSet {
        sources = sources == null ? List.of() : List.copyOf(sources);
        sourcePriority = sourcePriority == null ? List.of() : List.copyOf(sourcePriority);
        fieldRules = fieldRules == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(fieldRules));
    }

    /** Effective ordered sources for a field: field override if present, otherwise global. */
    public List<String> priorityFor(String field) {
        FieldRule override = fieldRules.get(field);
        if (override != null && !override.priority().isEmpty()) {
            return override.priority();
        }
        return sourcePriority;
    }

    /**
     * Structural validation used both at load time and defensively before resolution.
     * Throws {@link InvalidRuleSetException} with a distinguishable reason.
     */
    public void validate() {
        String rid = id == null ? "<null>" : id;
        if (id == null || id.isBlank()) {
            throw new InvalidRuleSetException(RuleRejectionReason.MISSING_RULE_SET_ID,
                    rid, "rule set id must be declared");
        }
        if (sourcePriority.isEmpty()) {
            throw new InvalidRuleSetException(RuleRejectionReason.MISSING_SOURCE_PRIORITY,
                    id, "sourcePriority must list at least one source");
        }
        List<String> seen = new ArrayList<>();
        for (String s : sourcePriority) {
            if (seen.contains(s)) {
                throw new InvalidRuleSetException(
                        RuleRejectionReason.DUPLICATE_SOURCE_IN_PRIORITY, id,
                        "source '" + s + "' appears more than once in sourcePriority " + sourcePriority);
            }
            seen.add(s);
        }
        for (String s : sourcePriority) {
            if (!sources.contains(s)) {
                throw new InvalidRuleSetException(
                        RuleRejectionReason.UNKNOWN_SOURCE_REFERENCED, id,
                        "sourcePriority references '" + s + "' not declared in sources " + sources);
            }
        }
        for (FieldRule fr : fieldRules.values()) {
            if (!List.of("symbol", "name", "sequence", "organism").contains(fr.field())) {
                throw new InvalidRuleSetException(
                        RuleRejectionReason.UNKNOWN_FIELD_REFERENCED, id,
                        "field rule references unsupported field '" + fr.field() + "'");
            }
            for (String s : fr.priority()) {
                if (!sources.contains(s)) {
                    throw new InvalidRuleSetException(
                            RuleRejectionReason.UNKNOWN_SOURCE_REFERENCED, id,
                            "field rule for '" + fr.field() + "' references undeclared source '"
                                    + s + "' not in sources " + sources);
                }
            }
            for (String s : fr.priority()) {
                if (fr.priority().indexOf(s) != fr.priority().lastIndexOf(s)) {
                    throw new InvalidRuleSetException(
                            RuleRejectionReason.DUPLICATE_SOURCE_IN_PRIORITY, id,
                            "field rule for '" + fr.field() + "' lists source '" + s
                                    + "' more than once: " + fr.priority());
                }
            }
        }
    }
}
