package com.github.lisama569.idempotenteventlogjava.tests;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.lisama569.idempotenteventlogjava.config.EventLogProperties;
import com.github.lisama569.idempotenteventlogjava.domain.AccountState;
import com.github.lisama569.idempotenteventlogjava.domain.StoredEvent;
import com.github.lisama569.idempotenteventlogjava.engine.EventLogService;
import com.github.lisama569.idempotenteventlogjava.engine.StateProjector;
import com.github.lisama569.idempotenteventlogjava.schema.VersionedPayloadCodec;
import com.github.lisama569.idempotenteventlogjava.store.FileEventStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 重放一致性与历史快照测试。
 */
class ReplayConsistencyTest {

    private static final Logger log = LoggerFactory.getLogger(ReplayConsistencyTest.class);

    @TempDir
    Path tempDir;

    private final ObjectMapper mapper = new ObjectMapper();

    private EventLogService newService() {
        EventLogProperties props = new EventLogProperties();
        props.setDataDir(tempDir.toString());
        var store = new FileEventStore(props, mapper);
        var codec = new VersionedPayloadCodec(mapper);
        var projector = new StateProjector(codec);
        EventLogService service = new EventLogService(store, codec, projector, mapper);
        service.rebuildForTest();
        return service;
    }

    private JsonNode p(String json) {
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void replayMatchesLiveState_andSnapshotsMatchHistory() {
        EventLogService service = newService();

        // 两个账户交错产生事件（混用 v1/v2 金额）
        service.append("k1", "ACCOUNT_OPENED", 2, p("{\"accountId\":\"a\"}"));   // seq1
        service.append("k2", "ACCOUNT_OPENED", 1, p("{\"accountId\":\"b\"}"));   // seq2
        service.append("k3", "DEPOSIT", 2, p("{\"accountId\":\"a\",\"amountCents\":1000}")); // seq3
        service.append("k4", "DEPOSIT", 1, p("{\"accountId\":\"b\",\"amountYuan\":\"5.50\"}")); // seq4 = 550
        service.append("k5", "WITHDRAWAL", 2, p("{\"accountId\":\"a\",\"amountCents\":300}")); // seq5 =>700
        service.append("k6", "DEPOSIT", 2, p("{\"accountId\":\"b\",\"amountCents\":50}"));     // seq6 =>600

        List<StoredEvent> logEvents = service.readLog(null);
        log.info("输入事件序列: 共 {} 条; 判定依据：实时状态必须等于全量重放", logEvents.size());
        logEvents.forEach(e -> log.info("  seq={} key={} type={} v{} raw={}",
                e.seq(), e.idempotencyKey(), e.eventType(), e.schemaVersion(), e.rawPayloadJson()));

        // 1) 实时状态与全量重放一致
        var verify = service.replayAndVerify();
        log.info("全量重放结果: eventCount={} consistent={} checkedAccounts={}",
                verify.eventCount(), verify.consistent(), verify.checkedAccounts());
        assertTrue(verify.consistent(), "重放状态必须与实时维护状态一致");

        // 2) 当前状态数值
        AccountState liveA = service.getCurrentState("a").orElseThrow();
        AccountState liveB = service.getCurrentState("b").orElseThrow();
        log.info("当前实时状态: a={} b={}", liveA, liveB);
        assertEquals(700L, liveA.balanceCents());
        assertEquals(600L, liveB.balanceCents());

        // 3) 任意历史节点快照：与按前缀重放的结果逐一比对
        for (long at = 0; at <= logEvents.size(); at++) {
            List<StoredEvent> prefix = service.readLog(at);
            var codec = new VersionedPayloadCodec(mapper);
            var projector = new StateProjector(codec);
            AccountState snapshotA = service.getSnapshot("a", at).orElse(null);
            AccountState prefixA = projector.project(prefix, "a").orElse(null);
            AccountState snapshotB = service.getSnapshot("b", at).orElse(null);
            AccountState prefixB = projector.project(prefix, "b").orElse(null);
            log.info("历史节点 seq={}: 快照 a={} b={}；前缀重放 a={} b={}",
                    at, snapshotA, snapshotB, prefixA, prefixB);
            assertEquals(prefixA, snapshotA, "at=" + at + " 账户 a 快照与重放不一致");
            assertEquals(prefixB, snapshotB, "at=" + at + " 账户 b 快照与重放不一致");
        }

        // 边界：seq=3 时 b 尚未存款
        assertEquals(0L, service.getSnapshot("b", 3).orElseThrow().balanceCents());
        assertEquals(1000L, service.getSnapshot("a", 3).orElseThrow().balanceCents());
        // seq=5 时 a=700，b 仍为 550
        assertEquals(700L, service.getSnapshot("a", 5).orElseThrow().balanceCents());
        assertEquals(550L, service.getSnapshot("b", 5).orElseThrow().balanceCents());
    }

    @Test
    void restart_rebuildsIdenticalStateFromLog() {
        EventLogService first = newService();
        first.append("r1", "ACCOUNT_OPENED", 2, p("{\"accountId\":\"c\"}"));
        first.append("r2", "DEPOSIT", 1, p("{\"accountId\":\"c\",\"amountYuan\":\"12.34\"}")); // 1234
        first.append("r3", "WITHDRAWAL", 2, p("{\"accountId\":\"c\",\"amountCents\":234}"));  // 1000

        AccountState beforeRestart = first.getCurrentState("c").orElseThrow();
        log.info("重启前实时状态: {}", beforeRestart);

        // 用同一数据目录重新装配：等价于进程重启
        EventLogService second = newService();
        AccountState afterRestart = second.getCurrentState("c").orElseThrow();
        log.info("重启后重放重建状态: {}；判定依据：两者必须完全相等", afterRestart);
        assertEquals(beforeRestart, afterRestart, "重启后从日志重建的状态必须一致");
        assertTrue(second.replayAndVerify().consistent());
    }

    @Test
    void snapshotRangeValidation() {
        EventLogService service = newService();
        service.append("z1", "ACCOUNT_OPENED", 2, p("{\"accountId\":\"z\"}"));
        assertThrows(com.github.lisama569.idempotenteventlogjava.domain.EventLogException.class,
                () -> service.getSnapshot("z", 99));
        assertThrows(com.github.lisama569.idempotenteventlogjava.domain.EventLogException.class,
                () -> service.getSnapshot("z", -1));
        assertTrue(service.getSnapshot("ghost", 1).isEmpty(),
                "未涉及的账户在任意节点都应是空，而非串号");
    }
}
