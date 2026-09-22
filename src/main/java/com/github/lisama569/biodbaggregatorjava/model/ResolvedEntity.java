package com.github.lisama569.biodbaggregatorjava.model;

import java.util.List;
import java.util.Map;

/**
 * 跨源解析后的一个实体簇。
 *
 * @param canonicalId   规范标识符，形式 namespace:value
 * @param type          实体类型
 * @param status        结论状态
 * @param identifiers   全部来源标识符映射 namespace -> value
 * @param attributes    各属性裁决
 * @param memberKeys    参与记录键
 * @param ambiguityNote 歧义/冲突说明
 */
public record ResolvedEntity(
        String canonicalId,
        EntityType type,
        ResolutionStatus status,
        Map<String, String> identifiers,
        Map<String, AttributeDecision> attributes,
        List<String> memberKeys,
        String ambiguityNote
) {
}
