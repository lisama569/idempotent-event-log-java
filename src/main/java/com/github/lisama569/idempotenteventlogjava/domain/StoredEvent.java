package com.github.lisama569.idempotenteventlogjava.domain;

/**
 * 已追加到日志中的事件（不可变记录）。
 *
 * @param seq              单调递增序列号，从 1 开始
 * @param idempotencyKey   幂等键
 * @param eventType        事件类型
 * @param schemaVersion    提交时的结构版本
 * @param rawPayloadJson   提交时的原始载荷 JSON（原样持久化）
 * @param canonicalContent 规范化内容指纹，用于同键冲突判定
 * @param createdAtMillis  写入时刻（毫秒）
 */
public record StoredEvent(long seq,
                          String idempotencyKey,
                          String eventType,
                          int schemaVersion,
                          String rawPayloadJson,
                          String canonicalContent,
                          long createdAtMillis) {
}
