package com.github.lisama569.biodbaggregatorjava.model;

/**
 * 某个属性值及其来源出处。
 */
public record SourceValue(
        String source,
        String primaryId,
        String value,
        long ordinal
) {
}
