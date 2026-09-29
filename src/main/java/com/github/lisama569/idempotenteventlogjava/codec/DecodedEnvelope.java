package com.github.lisama569.idempotenteventlogjava.codec;

import com.github.lisama569.idempotenteventlogjava.domain.EventPayload;

/**
 * 信封解码结果：canonical 载荷 + 原始声明 schemaVersion。
 */
public record DecodedEnvelope(
        String eventId,
        String idempotencyKey,
        String entityId,
        String type,
        int schemaVersion,
        EventPayload payload,
        long occurredAt
) {
}
