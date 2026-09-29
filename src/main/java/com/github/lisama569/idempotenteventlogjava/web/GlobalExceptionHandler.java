package com.github.lisama569.idempotenteventlogjava.web;

import com.github.lisama569.idempotenteventlogjava.error.EventLogException;
import com.github.lisama569.idempotenteventlogjava.error.RejectReason;
import com.github.lisama569.idempotenteventlogjava.web.dto.Responses;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 把带原因分类的领域异常映射为确定的 HTTP 状态码与错误体；
 * 客户端可依据 reason 字段区分失败原因。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    public GlobalExceptionHandler() {
    }

    @ExceptionHandler(EventLogException.class)
    public ResponseEntity<Responses.ErrorResponse> handle(EventLogException ex) {
        HttpStatus status = switch (ex.reason()) {
            case IDEMPOTENT_CONFLICT -> HttpStatus.CONFLICT;
            case STATE_NOT_FOUND, SNAPSHOT_OUT_OF_RANGE -> HttpStatus.NOT_FOUND;
            case BAD_JSON, SCHEMA_VIOLATION, ILLEGAL_ARGUMENT -> HttpStatus.BAD_REQUEST;
            case CORRUPT_LOG -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
        String errorName = ex.reason() == RejectReason.IDEMPOTENT_CONFLICT
                ? "idempotent_conflict"
                : ex.reason().name().toLowerCase();
        return ResponseEntity.status(status)
                .body(new Responses.ErrorResponse(errorName, ex.reason().name(), ex.getMessage()));
    }
}
