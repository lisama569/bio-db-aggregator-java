package com.github.lisama569.biodbaggregatorjava.model;

/** 跨源解析结论状态。 */
public enum ResolutionStatus {
    /** 所有来源一致，或冲突已按确定性优先级裁决。 */
    RESOLVED,
    /** 同一实体在同一来源内出现多份不一致记录，身份歧义无法裁决。 */
    AMBIGUOUS,
    /** 跨来源属性值冲突且优先级无法打破平局（保留全部冲突值）。 */
    CONFLICT
}
