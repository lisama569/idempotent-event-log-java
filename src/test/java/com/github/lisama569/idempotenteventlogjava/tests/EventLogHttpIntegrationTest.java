package com.github.lisama569.idempotenteventlogjava.tests;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP 端到端：成功/幂等/冲突/结构错误/快照/重放的状态码与 reason 分类。
 */
@SpringBootTest
class EventLogHttpIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(EventLogHttpIntegrationTest.class);

    @TempDir
    static Path tempDir;

    @Autowired
    private WebApplicationContext wac;

    private MockMvc mockMvc;

    @BeforeAll
    static void initDir() throws Exception {
        Files.createDirectories(tempDir);
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("eventlog.data-dir", () -> tempDir.toString());
    }

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac).build();
    }

    @Test
    void fullHttpFlow() throws Exception {
        String create = """
                {"eventId":"e1","idempotencyKey":"k1","entityId":"doc","type":"CREATE_DOCUMENT",
                 "schemaVersion":1,"payload":{"title":"T"}}""";
        String setV1 = """
                {"eventId":"e2","idempotencyKey":"k2","entityId":"doc","type":"SET_FIELD",
                 "schemaVersion":1,"payload":{"field":"a","value":"Hi"}}""";
        String setV2 = """
                {"eventId":"e3","idempotencyKey":"k3","entityId":"doc","type":"SET_FIELD",
                 "schemaVersion":2,"payload":{"field":"b","value":"x","transform":"UPPERCASE"}}""";

        log.info("[输入] POST /api/events create(v1): {}", create);
        mockMvc.perform(post("/api/events").contentType("application/json").content(create))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicate").value(false))
                .andExpect(jsonPath("$.seq").value(1));

        mockMvc.perform(post("/api/events").contentType("application/json").content(setV1))
                .andExpect(status().isOk()).andExpect(jsonPath("$.seq").value(2));
        mockMvc.perform(post("/api/events").contentType("application/json").content(setV2))
                .andExpect(status().isOk()).andExpect(jsonPath("$.seq").value(3));

        log.info("[输入] 重复提交 k1（相同内容）");
        mockMvc.perform(post("/api/events").contentType("application/json").content(create))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicate").value(true))
                .andExpect(jsonPath("$.seq").value(1));

        log.info("[输入] 冲突提交 k1（title 不同）");
        String conflict = create.replace("\"title\":\"T\"", "\"title\":\"OTHER\"");
        mockMvc.perform(post("/api/events").contentType("application/json").content(conflict))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reason").value("IDEMPOTENT_CONFLICT"));

        log.info("[输入] 结构错误：v2 缺 transform");
        String bad = """
                {"eventId":"e9","idempotencyKey":"k9","entityId":"doc","type":"SET_FIELD",
                 "schemaVersion":2,"payload":{"field":"a","value":"v"}}""";
        mockMvc.perform(post("/api/events").contentType("application/json").content(bad))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reason").value("SCHEMA_VIOLATION"));

        log.info("[输入] 非法 JSON");
        mockMvc.perform(post("/api/events").contentType("application/json").content("{oops"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reason").value("BAD_JSON"));

        log.info("[输入] GET 当前状态 / atSeq=1 快照 / replay");
        mockMvc.perform(get("/api/events/entities/doc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fields.b").value("X"))
                .andExpect(jsonPath("$.version").value(3));

        mockMvc.perform(get("/api/events/entities/doc/snapshot").param("atSeq", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("T"))
                .andExpect(jsonPath("$.fields.a").doesNotExist());

        mockMvc.perform(get("/api/events/entities/doc/snapshot").param("atSeq", "99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.reason").value("SNAPSHOT_OUT_OF_RANGE"));

        mockMvc.perform(get("/api/events/replay"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.consistent").value(true))
                .andExpect(jsonPath("$.replayedSeq").value(3));
        log.info("[判定] 全链路状态码与 reason 分类符合预期，replay 一致");
    }
}
