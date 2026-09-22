package com.github.lisama569.biodbaggregatorjava.model;

/**
 * 被拒绝/冲突记录及其可区分原因。
 *
 * @param source     来源名称
 * @param lineNumber 行号（从 1 开始；整文档级错误为 0）
 * @param rawLine    原始输入文本
 * @param reason     归类原因
 * @param detail     人类可读说明
 */
public record RejectedRecord(
        String source,
        int lineNumber,
        String rawLine,
        RejectReason reason,
        String detail
) {
}
