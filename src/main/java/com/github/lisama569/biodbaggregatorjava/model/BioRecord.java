package com.github.lisama569.biodbaggregatorjava.model;

import java.util.Map;

/**
 * 归一后的统一记录模型（一个来源中的一条基因/蛋白记录）。
 *
 * @param source      来源名称
 * @param type        实体类型
 * @param primaryId   记录在来源内的主键
 * @param identifiers 标识符映射，namespace -> value，至少包含来源自身命名空间
 * @param symbol      基因符号/蛋白简称（可空）
 * @param name        全称/描述（可空）
 * @param sequence    序列（可空）
 * @param ordinal     来源内行序号，用于确定性平局裁决
 */
public record BioRecord(
        String source,
        EntityType type,
        String primaryId,
        Map<String, String> identifiers,
        String symbol,
        String name,
        String sequence,
        long ordinal
) {
    public BioRecord {
        identifiers = identifiers == null ? Map.of() : Map.copyOf(identifiers);
    }

    /** 来源内唯一键。 */
    public String recordKey() {
        return source + ":" + type.name() + ":" + primaryId;
    }
}
