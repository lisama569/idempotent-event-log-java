package com.github.lisama569.idempotenteventlogjava.tests;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.lisama569.idempotenteventlogjava.codec.EventPayloadCodec;
import com.github.lisama569.idempotenteventlogjava.domain.DocumentState;
import com.github.lisama569.idempotenteventlogjava.domain.Event;
import com.github.lisama569.idempotenteventlogjava.error.EventLogException;
import com.github.lisama569.idempotenteventlogjava.error.RejectReason;
import com.github.lisama569.idempotenteventlogjava.service.DocumentProjector;
import com.github.lisama569.idempotenteventlogjava.service.EventLogService;
import com.github.lisama569.idempotenteventlogjava.store.FileEventLogStore;
import com.github.lisama569.idempotenteventlogjava.store.StoredEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 持久化与重放可复现：
 *  - 新旧 schema 版本混存于同一日志，重启后语义不变；
 *  - 重启重放与实时状态一致；
 *  - 内容哈希被篡改/半行写入/序列号断裂必须明确判定为 CORRUPT_LOG。
 */
class FileEventLogStoreRecoveryTest {

    private static final Logger log = LoggerFactory.getLogger(FileEventLogStoreRecoveryTest.class);

    @TempDir
    Path tempDir;

    private final ObjectMapper mapper = new ObjectMapper();
    private final EventPayloadCodec codec = new EventPayloadCodec();

    @Test
    void mixedV1V2LogReplaysIdenticallyAfterRestart() {
        // 第一次生命周期：v1 创建、v1 写字段、v2 写字段（UPPERCASE）
        FileEventLogStore first = new FileEventLogStore(tempDir, codec);
        var projector = new DocumentProjector();
        var service1 = new EventLogService(first, codec, projector);
        service1.append(node("""
                {"eventId":"e1","idempotencyKey":"k1","entityId":"doc","type":"CREATE_DOCUMENT",
                 "schemaVersion":1,"payload":{"title":"T"}}"""));
        service1.append(node("""
                {"eventId":"e2","idempotencyKey":"k2","entityId":"doc","type":"SET_FIELD",
                 "schemaVersion":1,"payload":{"field":"a","value":"Hello"}}"""));
        service1.append(node("""
                {"eventId":"e3","idempotencyKey":"k3","entityId":"doc","type":"SET_FIELD",
                 "schemaVersion":2,"payload":{"field":"b","value":"world","transform":"UPPERCASE"}}"""));
        DocumentState before = service1.currentState("doc");
        log.info("[输入] 重启前实时状态: {}", before);

        // 重启：仅依赖磁盘上的 JSONL 重建索引和状态
        FileEventLogStore reopened = new FileEventLogStore(tempDir, codec);
        var service2 = new EventLogService(reopened, codec, new DocumentProjector());
        DocumentState after = service2.currentState("doc");
        log.info("[判定] 重启后重放状态: {} | 与实时一致: {}", after, after.equals(before));
        assertEquals(before, after);
        assertEquals(Map.of("a", "Hello", "b", "WORLD"), after.fields());
        assertEquals(3, reopened.currentSeq());

        // 幂等索引也必须重建：旧键重复提交仍被识别
        var dup = service2.append(node("""
                {"eventId":"e1","idempotencyKey":"k1","entityId":"doc","type":"CREATE_DOCUMENT",
                 "schemaVersion":1,"payload":{"title":"T"}}"""));
        log.info("[判定] 重启后旧幂等键 k1 duplicate={}（依据: 启动扫描重建幂等索引）", dup.duplicate());
        assertEquals(true, dup.duplicate());
        assertEquals(3, reopened.currentSeq());
    }

    @Test
    void replayIsDeterministicForFixedPrefix() {
        FileEventLogStore store = new FileEventLogStore(tempDir, codec);
        var service = new EventLogService(store, codec, new DocumentProjector());
        service.append(node("""
                {"eventId":"e1","idempotencyKey":"k1","entityId":"doc","type":"CREATE_DOCUMENT",
                 "schemaVersion":1,"payload":{"title":"T"}}"""));
        service.append(node("""
                {"eventId":"e2","idempotencyKey":"k2","entityId":"doc","type":"SET_FIELD",
                 "schemaVersion":1,"payload":{"field":"a","value":"1"}}"""));
        service.append(node("""
                {"eventId":"e3","idempotencyKey":"k3","entityId":"doc","type":"SET_FIELD",
                 "schemaVersion":2,"payload":{"field":"a","value":"2","transform":"NONE"}}"""));
        List<StoredEvent> events = store.readAll();
        var projector = new DocumentProjector();
        Map<String, DocumentState> run1 = projector.replay(events, 2);
        Map<String, DocumentState> run2 = projector.replay(events, 2);
        log.info("[判定] 同一事件前缀重放两次结果相等: {} (依据: fold 不依赖时钟/外部状态)", run1.equals(run2));
        assertEquals(run1, run2);
        assertEquals("1", run1.get("doc").fields().get("a"));
    }

    @Test
    void tamperedContentHashRejectedAsCorrupt() throws Exception {
        FileEventLogStore store = new FileEventLogStore(tempDir, codec);
        writeRawLine(1, "deadbeef",
                """
                {"eventId":"e1","idempotencyKey":"k1","entityId":"doc","type":"CREATE_DOCUMENT",
                 "schemaVersion":1,"payload":{"title":"T"},"occurredAt":1}""");
        log.info("[输入] 手工写入 contentHash=deadbeef 的伪造行");
        EventLogException ex = assertThrows(EventLogException.class,
                () -> new FileEventLogStore(tempDir, codec));
        log.info("[判定] reason={} message={} (依据: canonical 重哈希不匹配)", ex.reason(), ex.getMessage());
        assertEquals(RejectReason.CORRUPT_LOG, ex.reason());
    }

    @Test
    void halfWrittenLineRejectedAsCorrupt() throws Exception {
        Path logFile = tempDir.resolve("events.log");
        Files.writeString(logFile, "{\"seq\":1,\"contentHash\":\"x\",\"eventId\":\"e");
        log.info("[输入] 模拟崩溃留下的半行: {}", Files.readString(logFile));
        EventLogException ex = assertThrows(EventLogException.class,
                () -> new FileEventLogStore(tempDir, codec));
        log.info("[判定] reason={} (依据: 半行不是完整 JSON)", ex.reason());
        assertEquals(RejectReason.CORRUPT_LOG, ex.reason());
    }

    @Test
    void schemaViolationInsideLogRejectedAsCorrupt() throws Exception {
        Path logFile = tempDir.resolve("events.log");
        // 直接拼出行内容，模拟磁盘上已存在的"缺 transform 的 v2 记录"（绕过写入路径的严格解码）
        String line = "{\"seq\":1,\"contentHash\":\"irrelevant\",\"eventId\":\"e1\","
                + "\"idempotencyKey\":\"k1\",\"entityId\":\"doc\",\"type\":\"SET_FIELD\","
                + "\"schemaVersion\":2,\"payload\":{\"field\":\"a\",\"value\":\"v\"},"
                + "\"occurredAt\":1}";
        Files.writeString(logFile, line + System.lineSeparator());
        log.info("[输入] 日志内出现缺 transform 的 v2 记录");
        EventLogException ex = assertThrows(EventLogException.class,
                () -> new FileEventLogStore(tempDir, codec));
        log.info("[判定] reason={} (依据: 重放走同一套严格 schema 规则)", ex.reason());
        assertEquals(RejectReason.CORRUPT_LOG, ex.reason());
    }

    private JsonNode node(String raw) {
        try {
            return mapper.readTree(raw);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** 按线上同样的行格式写一条事件，仅允许调用方伪造 contentHash。 */
    private void writeRawLine(long seq, String hash, String envelopeJson) throws Exception {
        Path logFile = tempDir.resolve("events.log");
        JsonNode envelope = mapper.readTree(envelopeJson);
        var decoded = codec.decodeEnvelope(envelope, 0L);
        Event event = new Event(decoded.eventId(), decoded.idempotencyKey(), decoded.entityId(),
                decoded.type(), decoded.schemaVersion(), decoded.payload(), decoded.occurredAt());
        ObjectNode line = mapper.createObjectNode();
        line.put("seq", seq);
        line.put("contentHash", hash);
        line.put("eventId", event.eventId());
        line.put("idempotencyKey", event.idempotencyKey());
        line.put("entityId", event.entityId());
        line.put("type", event.type());
        line.put("schemaVersion", event.schemaVersion());
        line.set("payload", codec.encodePayload(event.payload(), event.schemaVersion()));
        line.put("occurredAt", event.occurredAt());
        Files.writeString(logFile, mapper.writeValueAsString(line) + System.lineSeparator());
    }
}
