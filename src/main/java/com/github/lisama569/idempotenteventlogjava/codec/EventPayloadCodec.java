package com.github.lisama569.idempotenteventlogjava.codec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.lisama569.idempotenteventlogjava.domain.EventPayload;
import com.github.lisama569.idempotenteventlogjava.domain.Transform;
import com.github.lisama569.idempotenteventlogjava.error.EventLogException;
import com.github.lisama569.idempotenteventlogjava.error.RejectReason;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;

/**
 * 事件结构编解码。
 *
 * <p>设计口径（结构演进的确定结论）：
 * <ul>
 *   <li><b>缺字段</b>：必填字段缺失一律 SCHEMA_VIOLATION，不补默认值（v2 的 transform 缺失不猜）。</li>
 *   <li><b>多字段</b>：出现未声明字段一律 SCHEMA_VIOLATION，不静默丢弃。</li>
 *   <li><b>类型不符</b>：字段 JSON 类型与契约不符一律 SCHEMA_VIOLATION（含 null）。</li>
 *   <li><b>未知版本/未知事件类型</b>：一律 SCHEMA_VIOLATION，拒绝猜测语义。</li>
 *   <li><b>v1 → v2 迁移</b>：v1 的 SET_FIELD 没有 transform，唯一确定的迁移结果是
 *       {@link Transform#NONE}（原样写入）；canonical 内部表示与提交版本无关，
 *       因此 "v1 提交" 与 "v2 + transform=NONE" 语义等价、内容哈希相同。</li>
 *   <li><b>降级</b>：把带 transform（非 NONE）的 v2 数据渲染为 v1 会丢失语义，明确拒绝。</li>
 * </ul>
 */
public class EventPayloadCodec {

    private static final Set<String> ENVELOPE_FIELDS =
            Set.of("eventId", "idempotencyKey", "entityId", "type", "schemaVersion", "payload", "occurredAt");

    private static final Set<String> CREATE_FIELDS = Set.of("title");
    private static final Set<String> SET_V1_FIELDS = Set.of("field", "value");
    private static final Set<String> SET_V2_FIELDS = Set.of("field", "value", "transform");
    private static final Set<String> DELETE_FIELDS = Set.of();

    private final ObjectMapper mapper = new ObjectMapper();

    public EventPayloadCodec() {
    }

    /**
     * 解析并校验整条事件信封（HTTP 请求与日志重放共用同一套规则，保证重放可复现）。
     *
     * @param defaultOccurredAt 当请求未显式提供 occurredAt 时使用的时间（落盘时固定）
     */
    public DecodedEnvelope decodeEnvelope(JsonNode root, long defaultOccurredAt) {
        if (root == null || !root.isObject()) {
            throw new EventLogException(RejectReason.SCHEMA_VIOLATION,
                    "事件信封必须是 JSON 对象，实际: " + (root == null ? "null" : root.getNodeType()));
        }
        requireExactFields(root, ENVELOPE_FIELDS, "事件信封");
        String eventId = requireNonBlankString(root, "eventId");
        String idempotencyKey = requireNonBlankString(root, "idempotencyKey");
        String entityId = requireNonBlankString(root, "entityId");
        String type = requireNonBlankString(root, "type");
        int schemaVersion = requireVersion(root);
        JsonNode payloadNode = requireField(root, "payload");
        if (!payloadNode.isObject()) {
            throw new EventLogException(RejectReason.SCHEMA_VIOLATION,
                    "字段 'payload' 必须是 JSON 对象，实际: " + payloadNode.getNodeType());
        }
        long occurredAt = root.has("occurredAt") ? requireOccurredAt(root.get("occurredAt")) : defaultOccurredAt;
        EventPayload payload = decodePayload(type, schemaVersion, payloadNode);
        return new DecodedEnvelope(eventId, idempotencyKey, entityId, type, schemaVersion, payload, occurredAt);
    }

    /** 将 canonical 载荷按目标 schemaVersion 渲染为 JSON 树；降级会丢语义时明确拒绝。 */
    public JsonNode encodePayload(EventPayload payload, int schemaVersion) {
        if (schemaVersion < 1) {
            throw new EventLogException(RejectReason.SCHEMA_VIOLATION, "不支持的 schemaVersion: " + schemaVersion);
        }
        return switch (payload) {
            case EventPayload.CreateDocument c -> {
                if (schemaVersion > 2) {
                    throw new EventLogException(RejectReason.SCHEMA_VIOLATION,
                            "CREATE_DOCUMENT 不支持 schemaVersion=" + schemaVersion);
                }
                ObjectNode n = mapper.createObjectNode();
                n.put("title", c.title());
                yield n;
            }
            case EventPayload.SetField s -> {
                if (schemaVersion == 1) {
                    if (s.transform() != Transform.NONE) {
                        throw new EventLogException(RejectReason.SCHEMA_VIOLATION,
                                "SET_FIELD 无法降级到 v1：transform=" + s.transform()
                                        + " 在 v1 中不存在，降级会丢失语义");
                    }
                    ObjectNode n = mapper.createObjectNode();
                    n.put("field", s.field());
                    n.put("value", s.value());
                    yield n;
                }
                if (schemaVersion == 2) {
                    ObjectNode n = mapper.createObjectNode();
                    n.put("field", s.field());
                    n.put("value", s.value());
                    n.put("transform", s.transform().name());
                    yield n;
                }
                throw new EventLogException(RejectReason.SCHEMA_VIOLATION,
                        "SET_FIELD 不支持 schemaVersion=" + schemaVersion);
            }
            case EventPayload.DeleteDocument d -> {
                if (schemaVersion > 2) {
                    throw new EventLogException(RejectReason.SCHEMA_VIOLATION,
                            "DELETE_DOCUMENT 不支持 schemaVersion=" + schemaVersion);
                }
                yield mapper.createObjectNode();
            }
        };
    }

    /**
     * canonical 内容哈希（SHA-256 hex）。唯一决定同一幂等键下"内容是否一致"：
     * 只依赖 canonical 语义（entityId/type/载荷），与提交时声明的 schemaVersion 无关，
     * 故 v1 提交与其等价 v2 提交哈希相同。
     */
    public String contentHash(String entityId, String type, EventPayload payload) {
        ObjectNode canonical = mapper.createObjectNode();
        canonical.put("entityId", entityId);
        canonical.put("type", type);
        canonical.set("payload", canonicalPayload(payload));
        byte[] bytes;
        try {
            bytes = mapper.writeValueAsBytes(canonical);
        } catch (Exception e) {
            throw new IllegalStateException("canonical 序列化失败", e);
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private ObjectNode canonicalPayload(EventPayload payload) {
        return switch (payload) {
            case EventPayload.CreateDocument c -> {
                ObjectNode n = mapper.createObjectNode();
                n.put("title", c.title());
                yield n;
            }
            case EventPayload.SetField s -> {
                ObjectNode n = mapper.createObjectNode();
                n.put("field", s.field());
                n.put("value", s.value());
                n.put("transform", s.transform().name());
                yield n;
            }
            case EventPayload.DeleteDocument d -> mapper.createObjectNode();
        };
    }

    private EventPayload decodePayload(String type, int schemaVersion, JsonNode node) {
        switch (type) {
            case "CREATE_DOCUMENT" -> {
                if (schemaVersion != 1 && schemaVersion != 2) {
                    throw unsupportedVersion(type, schemaVersion, "1,2");
                }
                requireExactFields(node, CREATE_FIELDS, "CREATE_DOCUMENT 载荷");
                String title = requireNonBlankString(node, "title");
                return new EventPayload.CreateDocument(title);
            }
            case "SET_FIELD" -> {
                if (schemaVersion == 1) {
                    requireExactFields(node, SET_V1_FIELDS, "SET_FIELD(v1) 载荷");
                    String field = requireNonBlankString(node, "field");
                    String value = requireString(node, "value");
                    // v1 无 transform 字段：唯一确定的迁移结论是 NONE
                    return new EventPayload.SetField(field, value, Transform.NONE);
                }
                if (schemaVersion == 2) {
                    requireExactFields(node, SET_V2_FIELDS, "SET_FIELD(v2) 载荷");
                    String field = requireNonBlankString(node, "field");
                    String value = requireString(node, "value");
                    String transformRaw = requireNonBlankString(node, "transform");
                    Transform transform = Transform.fromWire(transformRaw);
                    return new EventPayload.SetField(field, value, transform);
                }
                throw unsupportedVersion(type, schemaVersion, "1,2");
            }
            case "DELETE_DOCUMENT" -> {
                if (schemaVersion != 1 && schemaVersion != 2) {
                    throw unsupportedVersion(type, schemaVersion, "1,2");
                }
                requireExactFields(node, DELETE_FIELDS, "DELETE_DOCUMENT 载荷");
                return new EventPayload.DeleteDocument();
            }
            default -> throw new EventLogException(RejectReason.SCHEMA_VIOLATION,
                    "未知事件类型: '" + type + "'，允许: CREATE_DOCUMENT/SET_FIELD/DELETE_DOCUMENT");
        }
    }

    private static EventLogException unsupportedVersion(String type, int version, String supported) {
        return new EventLogException(RejectReason.SCHEMA_VIOLATION,
                "事件 " + type + " 不支持 schemaVersion=" + version + "，支持版本: " + supported);
    }

    private static JsonNode requireField(JsonNode node, String name) {
        if (!node.has(name)) {
            throw new EventLogException(RejectReason.SCHEMA_VIOLATION, "缺少必填字段: '" + name + "'");
        }
        JsonNode v = node.get(name);
        if (v == null || v.isNull()) {
            throw new EventLogException(RejectReason.SCHEMA_VIOLATION, "字段 '" + name + "' 不能为 null");
        }
        return v;
    }

    private static String requireString(JsonNode node, String name) {
        JsonNode v = requireField(node, name);
        if (!v.isTextual()) {
            throw new EventLogException(RejectReason.SCHEMA_VIOLATION,
                    "字段 '" + name + "' 必须是字符串，实际: " + v.getNodeType());
        }
        return v.asText();
    }

    private static String requireNonBlankString(JsonNode node, String name) {
        String value = requireString(node, name);
        if (value.isBlank()) {
            throw new EventLogException(RejectReason.ILLEGAL_ARGUMENT,
                    "字段 '" + name + "' 不能为空白字符串");
        }
        return value;
    }

    private static int requireVersion(JsonNode node) {
        JsonNode v = requireField(node, "schemaVersion");
        if (!v.isIntegralNumber() || v.asLong() < 1 || v.asLong() > Integer.MAX_VALUE) {
            throw new EventLogException(RejectReason.SCHEMA_VIOLATION,
                    "字段 'schemaVersion' 必须是正整数，实际: " + v);
        }
        return v.intValue();
    }

    private static long requireOccurredAt(JsonNode v) {
        if (!v.isIntegralNumber() || v.asLong() < 0) {
            throw new EventLogException(RejectReason.SCHEMA_VIOLATION,
                    "字段 'occurredAt' 必须是非负整数（epoch millis），实际: " + v);
        }
        return v.longValue();
    }

    private static void requireExactFields(JsonNode node, Set<String> allowed, String what) {
        var it = node.fieldNames();
        while (it.hasNext()) {
            String name = it.next();
            if (!allowed.contains(name)) {
                throw new EventLogException(RejectReason.SCHEMA_VIOLATION,
                        what + "出现未声明字段: '" + name + "'，允许字段: " + allowed);
            }
        }
    }
}
