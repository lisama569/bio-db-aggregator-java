package com.github.lisama569.biodbaggregatorjava.ruleset;

import java.util.List;
import java.util.Map;

/**
 * One named, immutable, content-addressed set of merge/adjudication rules.
 *
 * <p>A rule set is loaded from a local resource file (never hard-coded in Java) and is
 * immutable once registered: changing the rules means publishing a new rule set with a
 * new id, so every historical conclusion stays tied to the exact rules that produced it.
 *
 * @param id             unique, stable identifier (also used as the stamped rulesVersion)
 * @param description    optional human-readable description
 * @param sourcePriority global source order, highest priority first
 * @param fields         optional per-field overrides keyed by canonical field name
 * @param contentHash    SHA-256 of the raw rule definition bytes, for exact reproduction
 */
public record RuleSet(
        String id,
        String description,
        List<String> sourcePriority,
        Map<String, FieldRule> fields,
        String contentHash
) {
    public RuleSet {
        sourcePriority = List.copyOf(sourcePriority);
        fields = fields == null ? Map.of() : Map.copyOf(fields);
    }

    /** Effective rule for a field: declared override or the global default. */
    public FieldRule fieldRule(String field) {
        return fields.getOrDefault(field, FieldRule.DEFAULT);
    }
}
