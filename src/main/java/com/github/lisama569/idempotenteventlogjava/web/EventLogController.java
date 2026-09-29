package com.github.lisama569.idempotenteventlogjava.web;

import com.github.lisama569.idempotenteventlogjava.domain.DocumentState;
import com.github.lisama569.idempotenteventlogjava.service.AppendResult;
import com.github.lisama569.idempotenteventlogjava.service.EventLogService;
import com.github.lisama569.idempotenteventlogjava.service.ReplayResult;
import com.github.lisama569.idempotenteventlogjava.web.dto.Responses;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 追加式事件日志 HTTP 接口（全部数据仅保存在本机文件中）。
 */
@RestController
@RequestMapping("/api/events")
public class EventLogController {

    private final EventLogService service;

    public EventLogController(EventLogService service) {
        this.service = service;
    }

    /**
     * 追加事件。同键同内容重复提交返回 200 + duplicate=true；
     * 同键异内容返回 409（IDEMPOTENT_CONFLICT）；结构问题返回 400（SCHEMA_VIOLATION）。
     */
    @PostMapping
    public ResponseEntity<Responses.AppendResponse> append(@RequestBody(required = false) String rawBody) {
        AppendResult result = service.appendRaw(rawBody);
        Responses.AppendResponse body = result.duplicate()
                ? Responses.AppendResponse.duplicate(result.storedEvent())
                : Responses.AppendResponse.accepted(result.storedEvent());
        return ResponseEntity.ok(body);
    }

    @GetMapping("/entities/{entityId}")
    public ResponseEntity<Responses.StateResponse> current(@PathVariable String entityId) {
        DocumentState state = service.currentState(entityId);
        return ResponseEntity.ok(Responses.StateResponse.of(state, service.currentSeq()));
    }

    /** 历史快照：atSeq 包含该序号事件作用后的状态。 */
    @GetMapping("/entities/{entityId}/snapshot")
    public ResponseEntity<Responses.StateResponse> snapshot(
            @PathVariable String entityId,
            @RequestParam("atSeq") long atSeq) {
        DocumentState state = service.snapshotAt(entityId, atSeq);
        return ResponseEntity.ok(Responses.StateResponse.of(state, atSeq));
    }

    /** 从日志全量重放并与实时状态比对。 */
    @GetMapping("/replay")
    public Responses.ReplayResponse replay() {
        ReplayResult result = service.verifyReplay();
        return new Responses.ReplayResponse(service.currentSeq(), result.replayedSeq(),
                true, result.states());
    }
}
