package com.github.lisama569.biodbaggregatorjava.rules;

import java.util.List;

/**
 * Per-field adjudication override.
 *
 * @param field    one of symbol/name/sequence/organism
 * @param priority sources consulted in order for this field; null/empty means the
 *                 rule-set level source priority applies
 */
public record FieldRule(String field, List<String> priority) {

    public FieldRule {
        priority = priority == null ? List.of() : List.copyOf(priority);
    }
}
