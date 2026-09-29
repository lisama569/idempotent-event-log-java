package com.github.lisama569.idempotenteventlogjava.store;

/**
 * 幂等键 -> 首次提交事件的索引项。
 */
public record IdempotentRecord(String idempotencyKey, String eventId, long seq, String contentHash) {
}
