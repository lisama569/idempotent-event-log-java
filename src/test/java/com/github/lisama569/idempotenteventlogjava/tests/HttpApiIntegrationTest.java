package com.github.lisama569.idempotenteventlogjava.tests;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Path;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * HTTP 协议层测试：验证幂等/冲突/非法参数等错误原因在响应中可区分。
 * 数据目录由 application-test.properties 指到 target 下，全部本机完成。
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class HttpApiIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(HttpApiIntegrationTest.class);

    /** 每次测试运行使用独立临时数据目录，保证可重复执行。 */
    @TempDir
    static Path tempDataDir;

    @DynamicPropertySource
    static void dataDir(DynamicPropertyRegistry registry) {
        registry.add("eventlog.data-dir", () -> tempDataDir.toString());
    }

    @Autowired
    private MockMvc mockMvc;

    private String request(String key, String type, Integer version, String payload) {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"idempotencyKey\":\"").append(key).append("\"");
        sb.append(",\"eventType\":\"").append(type).append("\"");
        if (version != null) {
            sb.append(",\"schemaVersion\":").append(version);
        }
        sb.append(",\"payload\":\"").append(payload.replace("\"", "\\\"")).append("\"");
        return sb.append("}").toString();
    }

    @Test
    @Order(1)
    void fullHttpFlow() throws Exception {
        String open = request("http-open", "ACCOUNT_OPENED", 2, "{\"accountId\":\"http-a\"}");
        log.info("HTTP 输入(开户): {}", open);
        mockMvc.perform(post("/api/events").contentType(MediaType.APPLICATION_JSON).content(open))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.deduped").value(false))
                .andExpect(jsonPath("$.event.seq").value(1));

        String deposit = request("http-dep", "DEPOSIT", 2,
                "{\"accountId\":\"http-a\",\"amountCents\":500}");
        log.info("HTTP 输入(存款): {}", deposit);
        mockMvc.perform(post("/api/events").contentType(MediaType.APPLICATION_JSON).content(deposit))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.event.seq").value(2));

        // 同键同内容：200 + deduped=true
        log.info("HTTP 输入(重复提交): {}", deposit);
        mockMvc.perform(post("/api/events").contentType(MediaType.APPLICATION_JSON).content(deposit))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deduped").value(true))
                .andExpect(jsonPath("$.event.seq").value(2));

        // 同键异内容：409 + reason=IDEMPOTENT_CONFLICT
        String conflict = request("http-dep", "DEPOSIT", 2,
                "{\"accountId\":\"http-a\",\"amountCents\":999}");
        log.info("HTTP 输入(同键冲突): {}", conflict);
        mockMvc.perform(post("/api/events").contentType(MediaType.APPLICATION_JSON).content(conflict))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reason").value("IDEMPOTENT_CONFLICT"));

        // 非法载荷：400 + reason=PAYLOAD_INVALID
        String invalid = request("http-bad", "DEPOSIT", 2, "{\"accountId\":\"http-a\"}");
        log.info("HTTP 输入(非法载荷): {}", invalid);
        mockMvc.perform(post("/api/events").contentType(MediaType.APPLICATION_JSON).content(invalid))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reason").value("PAYLOAD_INVALID"));

        // 当前状态
        mockMvc.perform(get("/api/accounts/http-a"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balanceCents").value(500));

        // 历史快照
        mockMvc.perform(get("/api/accounts/http-a/snapshot").param("atSequence", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balanceCents").value(0));

        // 不存在账户：404
        mockMvc.perform(get("/api/accounts/nobody"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.reason").value("ACCOUNT_NOT_FOUND"));

        // 重放校验
        mockMvc.perform(get("/api/admin/replay"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.consistent").value(true));

        log.info("HTTP 全流程判定: 201 生效 / 200 去重 / 409 冲突 / 400 非法 / 404 不存在，原因均可区分");
    }
}
