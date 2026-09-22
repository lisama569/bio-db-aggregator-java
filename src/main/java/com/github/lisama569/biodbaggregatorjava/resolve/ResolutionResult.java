package com.github.lisama569.biodbaggregatorjava.resolve;

import com.github.lisama569.biodbaggregatorjava.model.ResolvedEntity;

import java.util.List;
import java.util.Map;

/**
 * 一次解析的完整结果。
 *
 * @param rulesVersion           规则版本
 * @param entitiesByCanonicalId  canonicalId -> 实体
 * @param canonicalIdByIdentifier 任意标识符（namespace:value）-> canonicalId
 * @param ambiguousCount         歧义实体数
 * @param conflictCount          冲突实体数
 */
public record ResolutionResult(
        String rulesVersion,
        Map<String, ResolvedEntity> entitiesByCanonicalId,
        Map<String, String> canonicalIdByIdentifier,
        int ambiguousCount,
        int conflictCount
) {
}
