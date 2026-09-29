package com.github.lisama569.idempotenteventlogjava.domain;

/**
 * 经结构版本归一化后的事件语义，供状态投影使用。
 *
 * @param eventType 事件类型
 * @param accountId 账户 ID
 * @param amountCents 金额（分）；空操作事件为 0
 * @param schemaVersion 归一化前的原始结构版本
 */
public record NormalizedEvent(String eventType,
                              String accountId,
                              long amountCents,
                              int schemaVersion) {
}
