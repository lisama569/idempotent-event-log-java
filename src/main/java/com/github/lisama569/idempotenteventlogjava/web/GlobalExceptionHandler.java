package com.github.lisama569.idempotenteventlogjava.web;

import com.github.lisama569.idempotenteventlogjava.domain.ErrorReason;
import com.github.lisama569.idempotenteventlogjava.domain.EventLogException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * 统一异常到 HTTP 响应的映射。
 * 每种失败原因对应独立状态码，响应体带 reason，调用方可按原因区分处理。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(EventLogException.class)
    public ResponseEntity<Map<String, Object>> handle(EventLogException ex) {
        HttpStatus status = switch (ex.getReason()) {
            case IDEMPOTENT_CONFLICT, BUSINESS_CONFLICT -> HttpStatus.CONFLICT;
            case PAYLOAD_INVALID -> HttpStatus.BAD_REQUEST;
            case INVALID_PARAMETER -> HttpStatus.BAD_REQUEST;
            case ACCOUNT_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case INTERNAL_ERROR -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
        return ResponseEntity.status(status).body(Map.of(
                "error", true,
                "reason", ex.getReason().name(),
                "message", ex.getMessage() == null ? "" : ex.getMessage()));
    }
}
