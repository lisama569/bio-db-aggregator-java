package com.github.lisama569.biodbaggregatorjava.resolve;

import java.util.List;

/**
 * 确定性解析规则。版本化保存，历史结论可复现。
 *
 * @param rulesVersion     规则版本号
 * @param namespacePriority 标识符命名空间优先级（决定 canonicalId）
 * @param sourcePriority   来源优先级（决定属性裁决）
 */
public record ResolutionRules(
        String rulesVersion,
        List<String> namespacePriority,
        List<String> sourcePriority
) {
}
