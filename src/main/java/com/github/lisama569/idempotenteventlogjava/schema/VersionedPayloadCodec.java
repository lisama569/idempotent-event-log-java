package com.github.lisama569.idempotenteventlogjava.schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.lisama569.idempotenteventlogjava.domain.ErrorReason;
import com.github.lisama569.idempotenteventlogjava.domain.EventLogException;
import com.github.lisama569.idempotenteventlogjava.domain.NormalizedEvent;
import com.github.lisama569.idempotenteventlogjava.domain.StoredEvent;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 支持 v1/v2 两个结构版本的载荷编解码。
 *
 * <p>兼容口径：</p>
 * <ul>
 *   <li>v1：{@code accountId} 字符串 + {@code amountYuan} 两位小数字符串（元），适用于 DEPOSIT/WITHDRAWAL。</li>
 *   <li>v2：{@code accountId} 字符串 + {@code amountCents} 非负整数（分），适用于 DEPOSIT/WITHDRAWAL。</li>
 *   <li>ACCOUNT_OPENED（任意版本）：只允许 {@code accountId}。</li>
 *   <li>schemaVersion 缺省按 v1 处理。</li>
 * </ul>
 *
 * <p>严格校验：缺字段、多字段（未知字段）、类型不符、非法值、未知事件类型、
 * 未知版本一律以 PAYLOAD_INVALID 拒绝，不做静默丢弃。v1 的元按确定性汇率
 * （1 元 = 100 分）归一化为分，语义可复现。</p>
 */
@Component
public class VersionedPayloadCodec implements PayloadCodec {

    static final String TYPE_ACCOUNT_OPENED = "ACCOUNT_OPENED";
    static final String TYPE_DEPOSIT = "DEPOSIT";
    static final String TYPE_WITHDRAWAL = "WITHDRAWAL";

    private static final Set<String> KNOWN_EVENT_TYPES =
            Set.of(TYPE_ACCOUNT_OPENED, TYPE_DEPOSIT, TYPE_WITHDRAWAL);
    private static final Set<Integer> KNOWN_VERSIONS = Set.of(1, 2);

    private final ObjectMapper objectMapper;

    public VersionedPayloadCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public ParsedPayload parse(String eventType, Integer schemaVersion, JsonNode payload) {
        String type = requireEventType(eventType);
        int version = schemaVersion == null ? 1 : schemaVersion;
        if (!KNOWN_VERSIONS.contains(version)) {
            throw invalid("不支持的 schemaVersion=" + version + "，仅支持 1、2");
        }
        if (payload == null || !payload.isObject()) {
            throw invalid("payload 必须是 JSON 对象");
        }
        String accountId = requireAccountId(payload);

        long amountCents;
        String canonicalAmountField;
        if (TYPE_ACCOUNT_OPENED.equals(type)) {
            rejectUnknownFields(payload, Set.of("accountId"), version);
            amountCents = 0L;
            canonicalAmountField = "amountCents";
        } else if (version == 1) {
            rejectUnknownFields(payload, Set.of("accountId", "amountYuan"), version);
            amountCents = parseAmountYuan(payload);
            canonicalAmountField = "amountCents";
        } else {
            rejectUnknownFields(payload, Set.of("accountId", "amountCents"), version);
            amountCents = parseAmountCents(payload);
            canonicalAmountField = "amountCents";
        }

        // 规范化内容指纹：事件类型 + 归一化后的确定语义。
        // 跨版本的同一笔业务（v1 元 与 v2 分）若数值不同，指纹必然不同。
        Map<String, Object> canonical = new TreeMap<>();
        canonical.put("eventType", type);
        canonical.put("accountId", accountId);
        canonical.put(canonicalAmountField, amountCents);
        String canonicalContent = writeCanonical(canonical);

        return new ParsedPayload(
                new NormalizedEvent(type, accountId, amountCents, version),
                canonicalContent);
    }

    @Override
    public NormalizedEvent normalize(StoredEvent event) {
        // 重放走与写入完全相同的解析路径，保证旧版本数据语义确定、可复现。
        JsonNode node;
        try {
            node = objectMapper.readTree(event.rawPayloadJson());
        } catch (Exception e) {
            throw new EventLogException(ErrorReason.INTERNAL_ERROR,
                    "事件 seq=" + event.seq() + " 的原始载荷无法解析: " + e.getMessage());
        }
        ParsedPayload parsed = parse(event.eventType(), event.schemaVersion(), node);
        return parsed.normalized();
    }

    private String requireEventType(String eventType) {
        if (eventType == null || eventType.isBlank()) {
            throw new EventLogException(ErrorReason.INVALID_PARAMETER, "eventType 不能为空");
        }
        if (!KNOWN_EVENT_TYPES.contains(eventType)) {
            throw invalid("未知事件类型: " + eventType);
        }
        return eventType;
    }

    private String requireAccountId(JsonNode payload) {
        JsonNode node = payload.get("accountId");
        if (node == null || node.isNull()) {
            throw invalid("缺少必填字段 accountId");
        }
        if (!node.isTextual() || node.asText().isBlank()) {
            throw invalid("字段 accountId 必须是非空字符串");
        }
        return node.asText();
    }

    private void rejectUnknownFields(JsonNode payload, Set<String> allowed, int version) {
        Iterator<String> names = payload.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            if (!allowed.contains(name)) {
                throw invalid("v" + version + " 载荷存在未知字段: " + name);
            }
        }
    }

    private long parseAmountYuan(JsonNode payload) {
        JsonNode node = payload.get("amountYuan");
        if (node == null || node.isNull()) {
            throw invalid("v1 载荷缺少必填字段 amountYuan");
        }
        if (!node.isTextual()) {
            throw invalid("v1 字段 amountYuan 必须是字符串（如 \"12.34\"），禁止使用浮点数字面量");
        }
        BigDecimal yuan;
        try {
            yuan = new BigDecimal(node.asText());
        } catch (NumberFormatException e) {
            throw invalid("amountYuan 不是合法数字: " + node.asText());
        }
        if (yuan.signum() < 0) {
            throw invalid("amountYuan 不能为负数: " + node.asText());
        }
        if (yuan.stripTrailingZeros().scale() > 2) {
            throw invalid("amountYuan 最多保留两位小数: " + node.asText());
        }
        return yuan.multiply(BigDecimal.valueOf(100)).longValueExact();
    }

    private long parseAmountCents(JsonNode payload) {
        JsonNode node = payload.get("amountCents");
        if (node == null || node.isNull()) {
            throw invalid("v2 载荷缺少必填字段 amountCents");
        }
        if (!node.isIntegralNumber()) {
            throw invalid("v2 字段 amountCents 必须是整数");
        }
        long value = node.asLong();
        if (value < 0) {
            throw invalid("amountCents 不能为负数: " + value);
        }
        return value;
    }

    private String writeCanonical(Map<String, Object> canonical) {
        try {
            ObjectNode node = objectMapper.createObjectNode();
            canonical.forEach((k, v) -> node.put(k, v.toString()));
            return objectMapper.writeValueAsString(node);
        } catch (Exception e) {
            throw new EventLogException(ErrorReason.INTERNAL_ERROR,
                    "内容指纹生成失败: " + e.getMessage());
        }
    }

    private static EventLogException invalid(String message) {
        return new EventLogException(ErrorReason.PAYLOAD_INVALID, message);
    }
}
