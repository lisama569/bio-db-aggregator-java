package com.github.lisama569.biodbaggregatorjava.model;

import java.util.List;

/**
 * 单个属性（symbol/name/sequence）的确定性裁决结果。
 *
 * @param attribute  属性名
 * @param chosen     被选中的值（永不为 null，候选存在时）
 * @param basis      判定依据（优先级规则 + 平局规则，可复现）
 * @param candidates 全部候选值及出处
 * @param tieBreak   是否因来源优先级打平而启用了平局规则
 *                   （此时实体状态为 CONFLICT，但仍给出确定性结论，不静默取其一）
 */
public record AttributeDecision(
        String attribute,
        String chosen,
        String basis,
        List<SourceValue> candidates,
        boolean tieBreak
) {
}
