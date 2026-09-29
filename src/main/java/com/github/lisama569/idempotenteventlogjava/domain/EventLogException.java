package com.github.lisama569.idempotenteventlogjava.domain;

/**
 * 事件日志业务异常，携带可区分的失败原因。
 */
public class EventLogException extends RuntimeException {

    private final ErrorReason reason;

    public EventLogException(ErrorReason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public ErrorReason getReason() {
        return reason;
    }
}
