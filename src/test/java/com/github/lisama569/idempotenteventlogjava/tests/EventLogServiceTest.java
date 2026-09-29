package com.github.lisama569.idempotenteventlogjava.tests;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.lisama569.idempotenteventlogjava.codec.EventPayloadCodec;
import com.github.lisama569.idempotenteventlogjava.domain.DocumentState;
import com.github.lisama569.idempotenteventlogjava.error.EventLogException;
import com.github.lisama569.idempotenteventlogjava.error.RejectReason;
import com.github.lisama569.idempotenteventlogjava.service.DocumentProjector;
import com.github.lisama569.idempotenteventlogjava.service.EventLogService;
import com.github.lisama569.idempotenteventlogjava.store.FileEventLogStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.CyclicBarrier;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 写入幂等/冲突、并发去重、实时状态与重放一致、历史快照。
 */
class EventLogServiceTest {

    private static final Logger log = LoggerFactory.getLogger(EventLogServiceTest.class);

    @TempDir
    Path tempDir;

    private final ObjectMapper mapper = new ObjectMapper();
    private EventLogService service;

    @BeforeEach
    void setUp() {
        EventPayloadCodec codec = new EventPayloadCodec();
        var store = new FileEventLogStore(tempDir, codec);
        service = new EventLogService(store, codec, new DocumentProjector());
    }

    @AfterEach
    void tearDown() {
    }

    @Test
    void duplicateSameContentAppliedOnce() {
        JsonNode req = createEvent("k1", "doc1", 1, "{\"title\":\"T\"}");
        log.info("[输入] 同一请求提交两次: {}", req);
        var r1 = service.append(req);
        var r2 = service.append(req);
        log.info("[判定] 第一次 duplicate={} seq={}；第二次 duplicate={} seq={}",
                r1.duplicate(), r1.storedEvent().seq(), r2.duplicate(), r2.storedEvent().seq());
        assertFalse(r1.duplicate());
        assertTrue(r2.duplicate());
        assertEquals(1, service.currentSeq());
        assertEquals(1, service.events().size());
    }

    @Test
    void sameKeyDifferentContentIsConflict() {
        JsonNode first = createEvent("k1", "doc1", 1, "{\"title\":\"A\"}");
        JsonNode conflicting = createEvent("k1", "doc1", 1, "{\"title\":\"B\"}");
        log.info("[输入] first={} conflicting={}", first, conflicting);
        service.append(first);
        EventLogException ex = assertThrows(EventLogException.class, () -> service.append(conflicting));
        log.info("[判定] reason={} (依据: 同幂等键内容哈希不同 -> 冲突，非重复)", ex.reason());
        assertEquals(RejectReason.IDEMPOTENT_CONFLICT, ex.reason());
        assertEquals(1, service.events().size());
    }

    @Test
    void concurrentDuplicatesProduceExactlyOneRecord() throws Exception {
        int threads = 16;
        JsonNode req = createEvent("k-conc", "docC", 1, "{\"title\":\"CT\"}");
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        log.info("[输入] {} 个线程用同一幂等键并发提交同一内容: k-conc", threads);
        List<Callable<Object>> tasks = IntStream.range(0, threads).<Callable<Object>>mapToObj(i -> () -> {
            barrier.await();
            return service.append(req);
        }).toList();
        List<Future<Object>> futures = pool.invokeAll(tasks);
        long accepted = 0;
        long duplicates = 0;
        for (Future<Object> f : futures) {
            var result = f.get();
            var ar = (com.github.lisama569.idempotenteventlogjava.service.AppendResult) result;
            if (ar.duplicate()) {
                duplicates++;
            } else {
                accepted++;
            }
        }
        pool.shutdown();
        log.info("[判定] accepted={} duplicates={} 日志记录数={} (依据: store 单锁原子追加+幂等索引)",
                accepted, duplicates, service.events().size());
        assertEquals(1, accepted);
        assertEquals(threads - 1, duplicates);
        assertEquals(1, service.events().size());
    }

    @Test
    void replayMatchesLiveState() {
        service.append(createEvent("k1", "doc1", 1, "{\"title\":\"T1\"}"));
        service.append(setField("k2", "doc1", 1, "a", "Hello", null));
        service.append(setField("k3", "doc1", 2, "b", "world", "UPPERCASE"));
        service.append(createEvent("k4", "doc2", 1, "{\"title\":\"T2\"}"));
        log.info("[输入] 写入 4 个事件后 currentSeq={}", service.currentSeq());
        DocumentState live = service.currentState("doc1");
        var replay = service.verifyReplay();
        log.info("[判定] live doc1={} | replay replayedSeq={} consistent 比对通过", live, replay.replayedSeq());
        assertEquals(4, replay.replayedSeq());
        assertEquals(live, replay.states().get("doc1"));
        assertEquals("WORLD", live.fields().get("b"));
    }

    @Test
    void snapshotsAtAnyHistoricalPoint() {
        service.append(createEvent("k1", "doc1", 1, "{\"title\":\"T\"}"));
        service.append(setField("k2", "doc1", 1, "a", "v1", null));
        service.append(setField("k3", "doc1", 2, "a", "v2", "NONE"));
        log.info("[输入] 三次事件，检查 atSeq=1/2/3 的快照");
        DocumentState s1 = service.snapshotAt("doc1", 1);
        DocumentState s2 = service.snapshotAt("doc1", 2);
        DocumentState s3 = service.snapshotAt("doc1", 3);
        log.info("[判定] atSeq1 fields={} | atSeq2 a={} | atSeq3 a={}",
                s1.fields(), s2.fields().get("a"), s3.fields().get("a"));
        assertTrue(s1.fields().isEmpty());
        assertEquals("v1", s2.fields().get("a"));
        assertEquals("v2", s3.fields().get("a"));
        assertEquals(2, s2.version());
    }

    @Test
    void snapshotOutOfRangeHasDistinctReason() {
        service.append(createEvent("k1", "doc1", 1, "{\"title\":\"T\"}"));
        log.info("[输入] atSeq=99 超出范围（当前 1）");
        EventLogException ex = assertThrows(EventLogException.class,
                () -> service.snapshotAt("doc1", 99));
        log.info("[判定] reason={}", ex.reason());
        assertEquals(RejectReason.SNAPSHOT_OUT_OF_RANGE, ex.reason());
    }

    @Test
    void deleteHidesCurrentButSnapshotRemainsAvailable() {
        service.append(createEvent("k1", "doc1", 1, "{\"title\":\"T\"}"));
        JsonNode delete = event("k2", "doc1", "DELETE_DOCUMENT", 1, "{}");
        service.append(delete);
        log.info("[输入] 创建后删除；当前状态应 404，atSeq=1 快照仍可读");
        EventLogException ex = assertThrows(EventLogException.class, () -> service.currentState("doc1"));
        log.info("[判定] current -> reason={}", ex.reason());
        assertEquals(RejectReason.STATE_NOT_FOUND, ex.reason());
        DocumentState snapshot = service.snapshotAt("doc1", 1);
        assertEquals("T", snapshot.title());
        // 删除后允许用新事件重建
        service.append(createEvent("k3", "doc1", 2, "{\"title\":\"T2\"}"));
        DocumentState rebuilt = service.currentState("doc1");
        log.info("[判定] 重建后 title={} fields={} (依据: 删除后 CREATE 视为全新实体)",
                rebuilt.title(), rebuilt.fields());
        assertEquals("T2", rebuilt.title());
        assertTrue(rebuilt.fields().isEmpty());
    }

    @Test
    void lifecycleViolationIsRejectedBeforeWrite() {
        JsonNode illegal = setField("kX", "ghost", 1, "a", "v", null);
        log.info("[输入] 对不存在实体 SET_FIELD: {}", illegal);
        EventLogException ex = assertThrows(EventLogException.class, () -> service.append(illegal));
        log.info("[判定] reason={} (依据: 非法生命周期归 ILLEGAL_ARGUMENT，且不污染日志)", ex.reason());
        assertEquals(RejectReason.ILLEGAL_ARGUMENT, ex.reason());
        assertEquals(0, service.currentSeq());
    }

    private JsonNode createEvent(String key, String entityId, int version, String payloadJson) {
        return event(key, entityId, "CREATE_DOCUMENT", version, payloadJson);
    }

    private JsonNode setField(String key, String entityId, int version,
                              String field, String value, String transform) {
        String payload = transform == null
                ? "{\"field\":\"" + field + "\",\"value\":\"" + value + "\"}"
                : "{\"field\":\"" + field + "\",\"value\":\"" + value + "\",\"transform\":\"" + transform + "\"}";
        return event(key, entityId, "SET_FIELD", version, payload);
    }

    private JsonNode event(String key, String entityId, String type, int version, String payloadJson) {
        String body = "{\"eventId\":\"" + key + "-evt\",\"idempotencyKey\":\"" + key
                + "\",\"entityId\":\"" + entityId + "\",\"type\":\"" + type
                + "\",\"schemaVersion\":" + version + ",\"payload\":" + payloadJson + "}";
        try {
            return mapper.readTree(body);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
