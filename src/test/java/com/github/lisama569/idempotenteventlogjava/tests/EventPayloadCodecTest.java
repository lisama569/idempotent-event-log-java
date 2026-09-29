package com.github.lisama569.idempotenteventlogjava.tests;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.lisama569.idempotenteventlogjava.codec.DecodedEnvelope;
import com.github.lisama569.idempotenteventlogjava.codec.EventPayloadCodec;
import com.github.lisama569.idempotenteventlogjava.domain.EventPayload;
import com.github.lisama569.idempotenteventlogjava.domain.Transform;
import com.github.lisama569.idempotenteventlogjava.error.EventLogException;
import com.github.lisama569.idempotenteventlogjava.error.RejectReason;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 结构演进：缺字段/多字段/类型错误/未知版本/未知枚举的确定结论，
 * 以及 v1 -> v2 的迁移与等价哈希。
 */
class EventPayloadCodecTest {

    private static final Logger log = LoggerFactory.getLogger(EventPayloadCodecTest.class);

    private final ObjectMapper mapper = new ObjectMapper();
    private final EventPayloadCodec codec = new EventPayloadCodec();

    @Test
    void v1SetFieldMigratesToTransformNone() {
        JsonNode input = json("""
                {"eventId":"e1","idempotencyKey":"k1","entityId":"doc","type":"SET_FIELD",
                 "schemaVersion":1,"payload":{"field":"a","value":"Hello"}}
                """);
        log.info("[输入] v1 SET_FIELD: {}", input);
        DecodedEnvelope decoded = codec.decodeEnvelope(input, 123L);
        EventPayload.SetField setField = assertInstanceOf(EventPayload.SetField.class, decoded.payload());
        log.info("[判定] 迁移结果: field={}, value={}, transform={} (依据: v1 无 transform，唯一确定迁移为 NONE)",
                setField.field(), setField.value(), setField.transform());
        assertEquals("a", setField.field());
        assertEquals("Hello", setField.value());
        assertEquals(Transform.NONE, setField.transform());
    }

    @Test
    void v1AndV2NoneHaveSameCanonicalHash() {
        JsonNode v1 = json("""
                {"eventId":"e1","idempotencyKey":"k1","entityId":"doc","type":"SET_FIELD",
                 "schemaVersion":1,"payload":{"field":"a","value":"Hi"}}
                """);
        JsonNode v2 = json("""
                {"eventId":"e2","idempotencyKey":"k2","entityId":"doc","type":"SET_FIELD",
                 "schemaVersion":2,"payload":{"field":"a","value":"Hi","transform":"NONE"}}
                """);
        log.info("[输入] v1: {} | v2: {}", v1, v2);
        var d1 = codec.decodeEnvelope(v1, 0L);
        var d2 = codec.decodeEnvelope(v2, 0L);
        String h1 = codec.contentHash(d1.entityId(), d1.type(), d1.payload());
        String h2 = codec.contentHash(d2.entityId(), d2.type(), d2.payload());
        log.info("[判定] hash(v1)={} hash(v2+NONE)={} -> 语义等价必须相同", h1, h2);
        assertEquals(h1, h2);
    }

    @Test
    void v2TransformsAreRecognized() {
        JsonNode input = json("""
                {"eventId":"e","idempotencyKey":"k","entityId":"doc","type":"SET_FIELD",
                 "schemaVersion":2,"payload":{"field":"a","value":"MixCase","transform":"UPPERCASE"}}
                """);
        log.info("[输入] v2 SET_FIELD: {}", input);
        var decoded = codec.decodeEnvelope(input, 0L);
        var sf = assertInstanceOf(EventPayload.SetField.class, decoded.payload());
        log.info("[判定] transform={}，应用到值 -> {}", sf.transform(), sf.transform().apply(sf.value()));
        assertEquals(Transform.UPPERCASE, sf.transform());
        assertEquals("MIXCASE", sf.transform().apply(sf.value()));
    }

    @Test
    void missingFieldIsRejected() {
        JsonNode input = json("""
                {"eventId":"e","idempotencyKey":"k","entityId":"doc","type":"SET_FIELD",
                 "schemaVersion":2,"payload":{"field":"a","value":"v"}}
                """);
        log.info("[输入] v2 缺 transform: {}", input);
        EventLogException ex = assertThrows(EventLogException.class,
                () -> codec.decodeEnvelope(input, 0L));
        log.info("[判定] reason={} message={} (依据: v2 必填 transform，缺失不补默认值)",
                ex.reason(), ex.getMessage());
        assertEquals(RejectReason.SCHEMA_VIOLATION, ex.reason());
    }

    @Test
    void extraFieldIsRejected() {
        JsonNode input = json("""
                {"eventId":"e","idempotencyKey":"k","entityId":"doc","type":"CREATE_DOCUMENT",
                 "schemaVersion":1,"payload":{"title":"t","mystery":1}}
                """);
        log.info("[输入] 载荷含未声明字段: {}", input);
        EventLogException ex = assertThrows(EventLogException.class,
                () -> codec.decodeEnvelope(input, 0L));
        log.info("[判定] reason={} message={} (依据: 未声明字段不能静默丢弃)", ex.reason(), ex.getMessage());
        assertEquals(RejectReason.SCHEMA_VIOLATION, ex.reason());
    }

    @Test
    void unknownVersionIsRejected() {
        JsonNode input = json("""
                {"eventId":"e","idempotencyKey":"k","entityId":"doc","type":"SET_FIELD",
                 "schemaVersion":7,"payload":{"field":"a","value":"v"}}
                """);
        log.info("[输入] 未知 schemaVersion: {}", input);
        EventLogException ex = assertThrows(EventLogException.class,
                () -> codec.decodeEnvelope(input, 0L));
        log.info("[判定] reason={} message={}", ex.reason(), ex.getMessage());
        assertEquals(RejectReason.SCHEMA_VIOLATION, ex.reason());
    }

    @Test
    void unknownTransformIsRejected() {
        JsonNode input = json("""
                {"eventId":"e","idempotencyKey":"k","entityId":"doc","type":"SET_FIELD",
                 "schemaVersion":2,"payload":{"field":"a","value":"v","transform":"BOLD"}}
                """);
        log.info("[输入] 未知 transform: {}", input);
        EventLogException ex = assertThrows(EventLogException.class,
                () -> codec.decodeEnvelope(input, 0L));
        log.info("[判定] reason={} message={} (依据: 未知枚举不回退 NONE)", ex.reason(), ex.getMessage());
        assertEquals(RejectReason.SCHEMA_VIOLATION, ex.reason());
    }

    @Test
    void wrongFieldTypeIsRejected() {
        JsonNode input = json("""
                {"eventId":"e","idempotencyKey":"k","entityId":"doc","type":"CREATE_DOCUMENT",
                 "schemaVersion":1,"payload":{"title":123}}
                """);
        log.info("[输入] title 类型错误: {}", input);
        EventLogException ex = assertThrows(EventLogException.class,
                () -> codec.decodeEnvelope(input, 0L));
        log.info("[判定] reason={} message={}", ex.reason(), ex.getMessage());
        assertEquals(RejectReason.SCHEMA_VIOLATION, ex.reason());
    }

    @Test
    void blankKeyIsIllegalArgument() {
        JsonNode input = json("""
                {"eventId":"e","idempotencyKey":"  ","entityId":"doc","type":"DELETE_DOCUMENT",
                 "schemaVersion":1,"payload":{}}
                """);
        log.info("[输入] 空白 idempotencyKey: {}", input);
        EventLogException ex = assertThrows(EventLogException.class,
                () -> codec.decodeEnvelope(input, 0L));
        log.info("[判定] reason={} (依据: 结构合法但语义空白，归 ILLEGAL_ARGUMENT)", ex.reason());
        assertEquals(RejectReason.ILLEGAL_ARGUMENT, ex.reason());
    }

    @Test
    void downgradingNonNoneTransformIsRejected() {
        log.info("[输入] 尝试把 SET_FIELD(UPPERCASE) 渲染为 v1");
        EventPayload.SetField sf = new EventPayload.SetField("a", "v", Transform.UPPERCASE);
        EventLogException ex = assertThrows(EventLogException.class,
                () -> codec.encodePayload(sf, 1));
        log.info("[判定] reason={} message={} (依据: 降级会丢失 transform 语义)", ex.reason(), ex.getMessage());
        assertEquals(RejectReason.SCHEMA_VIOLATION, ex.reason());
    }

    private JsonNode json(String raw) {
        try {
            return mapper.readTree(raw);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
