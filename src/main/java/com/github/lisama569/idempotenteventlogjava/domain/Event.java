package com.github.lisama569.idempotenteventlogjava.domain;

/**
 * 事件信封（不含序列号；序列号由日志存储分配）。
 */
public record Event(
        String eventId,
        String idempotencyKey,
        String entityId,
        String type,
        int schemaVersion,
        EventPayload payload,
        long occurredAt
) {
}
