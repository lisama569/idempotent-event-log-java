package com.github.lisama569.idempotenteventlogjava.domain;

/**
 * 账户当前状态（事件重放投影结果）。
 *
 * @param accountId      账户 ID
 * @param balanceCents   余额（分）
 * @param version        已应用事件数
 * @param lastSequence   最后一条相关事件的序列号
 */
public record AccountState(String accountId,
                           long balanceCents,
                           long version,
                           long lastSequence) {
}
