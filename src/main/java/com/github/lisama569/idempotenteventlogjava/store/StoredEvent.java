package com.github.lisama569.idempotenteventlogjava.store;

import com.github.lisama569.idempotenteventlogjava.domain.Event;

/**
 * 已落盘事件：日志内的序号（从 1 开始、单调递增）与事件内容。
 */
public record StoredEvent(long seq, Event event, String contentHash) {
}
