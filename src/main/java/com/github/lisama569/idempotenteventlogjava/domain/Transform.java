package com.github.lisama569.idempotenteventlogjava.domain;

import com.github.lisama569.idempotenteventlogjava.error.EventLogException;
import com.github.lisama569.idempotenteventlogjava.error.RejectReason;

/**
 * SET_FIELD 事件中 value 的写入变换方式（schema v2 引入）。
 *
 * <p>语义：事件日志记录的是"发生过什么"。v1 没有 transform 字段，迁移时必须取一个
 * <b>确定的</b>值 NONE（原样写入），不能在读取端猜测；v2 起提交方必须显式声明，
 * 未知枚举名直接拒绝（SCHEMA_VIOLATION），不允许静默回退为 NONE。
 */
public enum Transform {
    NONE,
    UPPERCASE,
    LOWERCASE;

    /** 按线上枚举名解析，非法值明确拒绝而不是静默降级。 */
    public static Transform fromWire(String raw) {
        for (Transform t : values()) {
            if (t.name().equals(raw)) {
                return t;
            }
        }
        throw new EventLogException(RejectReason.SCHEMA_VIOLATION,
                "未知 transform 值: '" + raw + "'，允许值: NONE/UPPERCASE/LOWERCASE");
    }

    public String apply(String value) {
        return switch (this) {
            case NONE -> value;
            case UPPERCASE -> value.toUpperCase();
            case LOWERCASE -> value.toLowerCase();
        };
    }
}
