package com.github.lisama569.idempotenteventlogjava.error;

/**
 * 带明确原因分类的领域异常。
 */
public class EventLogException extends RuntimeException {

    private final RejectReason reason;

    public EventLogException(RejectReason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public EventLogException(RejectReason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public RejectReason reason() {
        return reason;
    }
}
