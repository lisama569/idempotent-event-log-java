package com.github.lisama569.idempotenteventlogjava.schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.lisama569.idempotenteventlogjava.domain.NormalizedEvent;

/**
 * 事件载荷的版本化编解码：负责结构校验、内容指纹与跨版本归一化。
 */
public interface PayloadCodec {

    /**
     * 校验并解析一次写入请求的载荷。
     * 缺字段、多字段、未知类型、未知版本、值非法均抛出 PAYLOAD_INVALID。
     */
    ParsedPayload parse(String eventType, Integer schemaVersion, JsonNode payload);

    /**
     * 把一条已存储事件的原始载荷归一化为当前语义（供重放投影）。
     */
    NormalizedEvent normalize(com.github.lisama569.idempotenteventlogjava.domain.StoredEvent event);

    /** 解析结果：归一化语义 + 规范化内容指纹。 */
    record ParsedPayload(NormalizedEvent normalized, String canonicalContent) {
    }
}
