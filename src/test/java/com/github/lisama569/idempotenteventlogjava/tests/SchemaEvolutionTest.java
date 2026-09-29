package com.github.lisama569.idempotenteventlogjava.tests;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.lisama569.idempotenteventlogjava.config.EventLogProperties;
import com.github.lisama569.idempotenteventlogjava.domain.ErrorReason;
import com.github.lisama569.idempotenteventlogjava.domain.EventLogException;
import com.github.lisama569.idempotenteventlogjava.engine.EventLogService;
import com.github.lisama569.idempotenteventlogjava.engine.StateProjector;
import com.github.lisama569.idempotenteventlogjava.schema.VersionedPayloadCodec;
import com.github.lisama569.idempotenteventlogjava.store.FileEventStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 事件结构演进（v1 元 / v2 分 并存）与严格校验测试。
 */
class SchemaEvolutionTest {

    private static final Logger log = LoggerFactory.getLogger(SchemaEvolutionTest.class);

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
    void v1YuanAndV2Cents_coexistAndNormalizeDeterministically() {
        EventLogService service = newService();
        service.append("e-open", "ACCOUNT_OPENED", 2, p("{\"accountId\":\"mix\"}"));

        // v1: "12.34" 元 -> 1234 分；v2: 566 分。混用后总额确定为 1800。
        service.append("e-v1", "DEPOSIT", 1, p("{\"accountId\":\"mix\",\"amountYuan\":\"12.34\"}"));
        service.append("e-v2", "DEPOSIT", 2, p("{\"accountId\":\"mix\",\"amountCents\":566}"));

        long balance = service.getCurrentState("mix").orElseThrow().balanceCents();
        log.info("v1/v2 并存输入: amountYuan=12.34(v1) + amountCents=566(v2) => 归一化余额={} 分（判定依据：12.34*100+566=1800）", balance);
        assertEquals(1800L, balance);

        // 旧版本数据经重放必须还原出同样语义（可复现）
        assertTrue(service.replayAndVerify().consistent());

        // 日志里原始 v1 文本仍按原样保留
        var storedV1 = service.readLog(null).get(1);
        assertEquals(1, storedV1.schemaVersion());
        assertTrue(storedV1.rawPayloadJson().contains("amountYuan"));
        log.info("v1 原始数据原样保留: {}", storedV1.rawPayloadJson());
    }

    @Test
    void missingAndExtraFields_areRejectedNotSilentlyDropped() {
        EventLogService service = newService();

        EventLogException missingV1 = assertThrows(EventLogException.class,
                () -> service.append("m1", "DEPOSIT", 1, p("{\"accountId\":\"x\"}")));
        log.info("v1 缺 amountYuan => reason={} message={}", missingV1.getReason(), missingV1.getMessage());
        assertEquals(ErrorReason.PAYLOAD_INVALID, missingV1.getReason());

        EventLogException missingV2 = assertThrows(EventLogException.class,
                () -> service.append("m2", "DEPOSIT", 2, p("{\"accountId\":\"x\",\"amountYuan\":\"1\"}")));
        log.info("v2 携带旧字段名 amountYuan（对 v2 属于未知字段）=> reason={} message={}",
                missingV2.getReason(), missingV2.getMessage());
        assertEquals(ErrorReason.PAYLOAD_INVALID, missingV2.getReason());

        EventLogException extra = assertThrows(EventLogException.class,
                () -> service.append("m3", "DEPOSIT", 2,
                        p("{\"accountId\":\"x\",\"amountCents\":1,\"currency\":\"CNY\"}")));
        log.info("v2 多字段 currency => reason={} message={}", extra.getReason(), extra.getMessage());
        assertEquals(ErrorReason.PAYLOAD_INVALID, extra.getReason());

        assertTrue(service.readLog(null).isEmpty(), "任何结构不确定的输入都不得落盘");
    }

    @Test
    void valueSemantics_areStrictAndDeterministic() {
        EventLogService service = newService();
        service.append("o", "ACCOUNT_OPENED", 2, p("{\"accountId\":\"s\"}"));

        // v1 浮点字面量拒绝（避免二进制浮点歧义），只接受字符串
        EventLogException floatLiteral = assertThrows(EventLogException.class,
                () -> service.append("f1", "DEPOSIT", 1,
                        p("{\"accountId\":\"s\",\"amountYuan\":1.23}")));
        assertEquals(ErrorReason.PAYLOAD_INVALID, floatLiteral.getReason());
        log.info("v1 浮点字面量 => reason={} message={}", floatLiteral.getReason(), floatLiteral.getMessage());

        // v1 超过两位小数拒绝：含义不明确，不做静默舍入
        EventLogException tooPrecise = assertThrows(EventLogException.class,
                () -> service.append("f2", "DEPOSIT", 1,
                        p("{\"accountId\":\"s\",\"amountYuan\":\"1.234\"}")));
        assertEquals(ErrorReason.PAYLOAD_INVALID, tooPrecise.getReason());
        log.info("v1 三位小数 => reason={} message={}", tooPrecise.getReason(), tooPrecise.getMessage());

        // 非法数字、负数
        EventLogException notANumber = assertThrows(EventLogException.class,
                () -> service.append("f3", "DEPOSIT", 1,
                        p("{\"accountId\":\"s\",\"amountYuan\":\"abc\"}")));
        assertEquals(ErrorReason.PAYLOAD_INVALID, notANumber.getReason());

        EventLogException negative = assertThrows(EventLogException.class,
                () -> service.append("f4", "DEPOSIT", 2,
                        p("{\"accountId\":\"s\",\"amountCents\":-1}")));
        assertEquals(ErrorReason.PAYLOAD_INVALID, negative.getReason());
        log.info("负数金额 => reason={}", negative.getReason());

        // 未知版本必须有确定结论：拒绝
        EventLogException badVersion = assertThrows(EventLogException.class,
                () -> service.append("f5", "DEPOSIT", 99,
                        p("{\"accountId\":\"s\",\"amountCents\":1}")));
        assertEquals(ErrorReason.PAYLOAD_INVALID, badVersion.getReason());
        log.info("未知 schemaVersion=99 => reason={} message={}", badVersion.getReason(), badVersion.getMessage());

        assertEquals(1, service.readLog(null).size(), "只有开户事件落盘");
    }

    @Test
    void sameKeyAcrossVersions_differentCanonicalMeaning_isConflict() {
        EventLogService service = newService();
        service.append("o2", "ACCOUNT_OPENED", 2, p("{\"accountId\":\"v\"}"));

        // 同键：v1 10.00 元 与 v2 10 分 字面值相近但语义差 100 倍，必须判为冲突
        service.append("cross", "DEPOSIT", 1, p("{\"accountId\":\"v\",\"amountYuan\":\"10.00\"}"));
        EventLogException ex = assertThrows(EventLogException.class,
                () -> service.append("cross", "DEPOSIT", 2,
                        p("{\"accountId\":\"v\",\"amountCents\":10}")));
        log.info("同键跨版本语义不同: v1=10.00元(1000分) vs v2=10分 => reason={} message={}",
                ex.getReason(), ex.getMessage());
        assertEquals(ErrorReason.IDEMPOTENT_CONFLICT, ex.getReason());
        assertEquals(1000L, service.getCurrentState("v").orElseThrow().balanceCents());
    }
}
