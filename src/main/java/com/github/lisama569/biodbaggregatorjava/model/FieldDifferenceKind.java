package com.github.lisama569.biodbaggregatorjava.model;

/**
 * What changed for one adjudicated field between two rule sets applied to the same data.
 *
 * <ul>
 *   <li>{@link #WINNER_SOURCE_CHANGED}: the winning value is identical, but the source
 *       credited as winner changed (including attribution on a single-source field
 *       being present on only one side, or a field absent on one side without a value).</li>
 *   <li>{@link #VALUE_CHANGED}: the winning value changed; the winning source stayed
 *       the same (or source attribution is unavailable on one side of an absent field).</li>
 *   <li>{@link #VALUE_AND_SOURCE_CHANGED}: both winning value and winning source changed.</li>
 * </ul>
 */
public enum FieldDifferenceKind {
    WINNER_SOURCE_CHANGED,
    VALUE_CHANGED,
    VALUE_AND_SOURCE_CHANGED
}
