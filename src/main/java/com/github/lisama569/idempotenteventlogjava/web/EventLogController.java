package com.github.lisama569.idempotenteventlogjava.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.lisama569.idempotenteventlogjava.domain.AccountState;
import com.github.lisama569.idempotenteventlogjava.domain.AppendOutcome;
import com.github.lisama569.idempotenteventlogjava.domain.ErrorReason;
import com.github.lisama569.idempotenteventlogjava.domain.EventLogException;
import com.github.lisama569.idempotenteventlogjava.domain.StoredEvent;
import com.github.lisama569.idempotenteventlogjava.engine.EventLogService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 事件日志 HTTP 接口（全部数据驻留本机）。
 */
@RestController
@RequestMapping("/api")
public class EventLogController {

    private final EventLogService eventLogService;
    private final ObjectMapper objectMapper;

    public EventLogController(EventLogService eventLogService, ObjectMapper objectMapper) {
        this.eventLogService = eventLogService;
        this.objectMapper = objectMapper;
    }

    /** 追加事件：201 新写入，200 幂等去重，409 同键内容冲突。 */
    @PostMapping("/events")
    public ResponseEntity<Map<String, Object>> append(@RequestBody AppendEventRequest request) {
        JsonNode payload = parsePayload(request.getPayload());
        AppendOutcome outcome = eventLogService.append(
                request.getIdempotencyKey(), request.getEventType(),
                request.getSchemaVersion(), payload);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("deduped", outcome.deduped());
        body.put("event", toEventView(outcome.event()));
        return ResponseEntity.status(outcome.deduped() ? HttpStatus.OK : HttpStatus.CREATED).body(body);
    }

    @GetMapping("/accounts/{accountId}")
    public AccountState getState(@PathVariable String accountId) {
        return eventLogService.getCurrentState(accountId)
                .orElseThrow(() -> new EventLogException(ErrorReason.ACCOUNT_NOT_FOUND,
                        "账户不存在: " + accountId));
    }

    @GetMapping("/accounts/{accountId}/snapshot")
    public AccountState getSnapshot(@PathVariable String accountId,
                                    @RequestParam long atSequence) {
        return eventLogService.getSnapshot(accountId, atSequence)
                .orElseThrow(() -> new EventLogException(ErrorReason.ACCOUNT_NOT_FOUND,
                        "截至 seq=" + atSequence + " 时账户不存在: " + accountId));
    }

    @GetMapping("/events")
    public Map<String, Object> listEvents(@RequestParam(required = false) Long atSequence) {
        List<StoredEvent> events = eventLogService.readLog(atSequence);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("count", events.size());
        body.put("events", events.stream().map(this::toEventView).toList());
        return body;
    }

    @GetMapping("/admin/replay")
    public Map<String, Object> replay() {
        EventLogService.ReplayVerification verification = eventLogService.replayAndVerify();
        return Map.of(
                "eventCount", verification.eventCount(),
                "checkedAccounts", verification.checkedAccounts(),
                "consistent", verification.consistent());
    }

    private JsonNode parsePayload(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new EventLogException(ErrorReason.PAYLOAD_INVALID, "payload 不能为空");
        }
        try {
            JsonNode node = objectMapper.readTree(raw);
            if (node == null || !node.isObject()) {
                throw new EventLogException(ErrorReason.PAYLOAD_INVALID, "payload 必须是 JSON 对象");
            }
            return node;
        } catch (EventLogException e) {
            throw e;
        } catch (Exception e) {
            throw new EventLogException(ErrorReason.PAYLOAD_INVALID,
                    "payload 不是合法 JSON: " + e.getMessage());
        }
    }

    private Map<String, Object> toEventView(StoredEvent e) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("seq", e.seq());
        view.put("idempotencyKey", e.idempotencyKey());
        view.put("eventType", e.eventType());
        view.put("schemaVersion", e.schemaVersion());
        view.put("payload", parseRaw(e.rawPayloadJson()));
        view.put("canonicalContent", e.canonicalContent());
        view.put("createdAtMillis", e.createdAtMillis());
        return view;
    }

    private JsonNode parseRaw(String raw) {
        try {
            return objectMapper.readTree(raw);
        } catch (Exception e) {
            return objectMapper.getNodeFactory().textNode(raw);
        }
    }
}
