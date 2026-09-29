package com.github.lisama569.idempotenteventlogjava.domain;

import java.util.Map;

/**
 * 文档当前状态/历史快照。version 为最后作用于该实体的事件序列号，0 表示尚未创建。
 */
public record DocumentState(
        String entityId,
        String title,
        Map<String, String> fields,
        long version,
        boolean deleted
) {
}
