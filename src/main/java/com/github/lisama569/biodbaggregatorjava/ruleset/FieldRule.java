package com.github.lisama569.biodbaggregatorjava.ruleset;

import java.util.List;

/**
 * Adjudication rule for one canonical field (symbol/name/sequence/organism).
 *
 * @param strategy how the winning value is chosen
 * @param priority optional per-field source order; sources not listed fall back to the
 *                 rule set's global sourcePriority. An empty list means "use global order".
 */
public record FieldRule(FieldResolutionStrategy strategy, List<String> priority) {

    public static final FieldRule DEFAULT =
            new FieldRule(FieldResolutionStrategy.SOURCE_PRIORITY, List.of());

    public FieldRule {
        priority = priority == null ? List.of() : List.copyOf(priority);
    }
}
