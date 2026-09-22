package com.github.lisama569.biodbaggregatorjava.model;

/**
 * 记录被拒绝或冲突的原因分类，必须可区分，不得静默丢弃。
 */
public enum RejectReason {
    /** 行/文档无法解析，例如非法 JSON、列数不匹配。 */
    MALFORMED_RECORD,
    /** 缺少必填字段或字段值非法（如未知实体类型）。 */
    MISSING_REQUIRED_FIELD,
    /** 同一来源内主键重复且内容不一致。 */
    WITHIN_SOURCE_ID_CONFLICT,
    /** 不支持的来源格式。 */
    UNSUPPORTED_FORMAT
}
