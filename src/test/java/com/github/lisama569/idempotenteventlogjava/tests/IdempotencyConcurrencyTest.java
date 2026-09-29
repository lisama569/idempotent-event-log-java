package com.github.lisama569.idempotenteventlogjava.tests;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.lisama569.idempotenteventlogjava.config.EventLogProperties;
import com.github.lisama569.idempotenteventlogjava.domain.AppendOutcome;
import com.github.lisama569.idempotenteventlogjava.domain.ErrorReason;
import com.github.lisama569.idempotenteventlogjava.domain.EventLogException;
import com.github.lisama569.idempotenteventlogjava.engine.EventLogService;
import com.github.lisama569.idempotenteventlogjava.engine.StateProjector;
import com.github.lisama569.idempotenteventlogjava.schema.VersionedPayloadCodec;
import com.github.lisama569.idempotenteventlogjava.store.FileEventStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 幂等与并发重复写入测试。
 * 单测日志打印每次输入与判定依据（生效 / 去重 / 冲突 / 非法）。
 */
class IdempotencyConcurrencyTest {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyConcurrencyTest.class);

    @TempDir
    Path tempDir;

    private EventLogService service;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        EventLogProperties props = new EventLogProperties();
        props.setDataDir(tempDir.toString());
        var store = new FileEventStore(props, mapper);
        var codec = new VersionedPayloadCodec(mapper);
        var projector = new StateProjector(codec);
        service = new EventLogService(store, codec, projector, mapper);
        service.rebuildForTest();
    }

    private JsonNode payload(String json) {
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void openAccount(String key, String accountId) {
        service.append(key, "ACCOUNT_OPENED", 2,
                payload("{\"accountId\":\"" + accountId + "\"}"));
    }

    @Test
    void concurrentDuplicates_applyExactlyOnce() throws Exception {
        openAccount("key-open-a", "acct-a");

        int threads = 16;
        int perThread = 5;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger applied = new AtomicInteger();
        AtomicInteger deduped = new AtomicInteger();
        List<Throwable> failures = new ArrayList<>();

        for (int t = 0; t < threads; t++) {
            int threadId = t;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        JsonNode input = payload("{\"accountId\":\"acct-a\",\"amountCents\":100}");
                        AppendOutcome outcome = service.append(
                                "same-key", "DEPOSIT", 2, input);
                        if (outcome.deduped()) {
                            deduped.incrementAndGet();
                        } else {
                            applied.incrementAndGet();
                        }
                        log.info("线程{} 第{}次: key=same-key 输入={} => seq={} deduped={}",
                                threadId, i, input, outcome.event().seq(), outcome.deduped());
                    }
                } catch (Throwable e) {
                    synchronized (failures) {
                        failures.add(e);
                    }
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        log.info("判定汇总: 生效={} 去重={} 失败={}（依据：同键同内容只应生效一次）",
                applied.get(), deduped.get(), failures.size());
        assertTrue(failures.isEmpty(), "并发重复写入不应抛异常: " + failures);
        assertEquals(1, applied.get(), "同键并发提交只能生效一次");
        assertEquals(threads * perThread - 1, deduped.get(), "其余全部命中去重");
        assertEquals(2, service.readLog(null).size(), "日志中只有 开户+1 条存款");
        assertEquals(100L, service.getCurrentState("acct-a").orElseThrow().balanceCents(),
                "余额只应增加一次");
    }

    @Test
    void sameKeyDifferentContent_isConflictWithDistinctReason() {
        openAccount("key-x", "acct-b");
        JsonNode first = payload("{\"accountId\":\"acct-b\",\"amountCents\":100}");
        JsonNode second = payload("{\"accountId\":\"acct-b\",\"amountCents\":200}");

        AppendOutcome ok = service.append("dep-key", "DEPOSIT", 2, first);
        log.info("首次输入: key=dep-key payload={} => 生效 seq={}", first, ok.event().seq());

        EventLogException ex = assertThrows(EventLogException.class,
                () -> service.append("dep-key", "DEPOSIT", 2, second));
        log.info("冲突输入: key=dep-key payload={} => 拒绝 reason={} message={}",
                second, ex.getReason(), ex.getMessage());

        assertEquals(ErrorReason.IDEMPOTENT_CONFLICT, ex.getReason(),
                "同键异内容必须以 IDEMPOTENT_CONFLICT 区分");
        assertEquals(2, service.readLog(null).size(), "冲突提交不得产生新记录");
        assertEquals(100L, service.getCurrentState("acct-b").orElseThrow().balanceCents());
    }

    @Test
    void invalidPayload_reasonIsPayloadInvalid() {
        EventLogException missing = assertThrows(EventLogException.class,
                () -> service.append("bad-1", "DEPOSIT", 2,
                        payload("{\"accountId\":\"acct-c\"}")));
        log.info("输入缺字段 => reason={} message={}", missing.getReason(), missing.getMessage());
        assertEquals(ErrorReason.PAYLOAD_INVALID, missing.getReason());

        EventLogException extra = assertThrows(EventLogException.class,
                () -> service.append("bad-2", "ACCOUNT_OPENED", 2,
                        payload("{\"accountId\":\"acct-c\",\"surprise\":1}")));
        log.info("输入多字段 => reason={} message={}", extra.getReason(), extra.getMessage());
        assertEquals(ErrorReason.PAYLOAD_INVALID, extra.getReason());

        EventLogException badType = assertThrows(EventLogException.class,
                () -> service.append("bad-3", "TRANSFER", 2,
                        payload("{\"accountId\":\"acct-c\"}")));
        log.info("未知事件类型 => reason={} message={}", badType.getReason(), badType.getMessage());
        assertEquals(ErrorReason.PAYLOAD_INVALID, badType.getReason());

        assertTrue(service.readLog(null).isEmpty(), "非法输入不得写入日志");
    }

    @Test
    void businessRules_conflictAndNotFoundAreDistinct() {
        openAccount("open-d", "acct-d");
        EventLogException duplicate = assertThrows(EventLogException.class,
                () -> service.append("open-d-2", "ACCOUNT_OPENED", 2,
                        payload("{\"accountId\":\"acct-d\"}")));
        log.info("重复开户 => reason={}", duplicate.getReason());
        assertEquals(ErrorReason.BUSINESS_CONFLICT, duplicate.getReason());

        EventLogException overdraw = assertThrows(EventLogException.class,
                () -> service.append("wd-1", "WITHDRAWAL", 2,
                        payload("{\"accountId\":\"acct-d\",\"amountCents\":50}")));
        log.info("余额不足取款 => reason={} message={}", overdraw.getReason(), overdraw.getMessage());
        assertEquals(ErrorReason.BUSINESS_CONFLICT, overdraw.getReason());
    }
}
