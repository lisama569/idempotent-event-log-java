package com.github.lisama569.idempotenteventlogjava.service;

import com.github.lisama569.idempotenteventlogjava.store.StoredEvent;

/**
 * 写入结果：storedEvent 为本次实际生效（首次或首次提交对应的那条）事件；
 * duplicate=true 表示本次提交命中了已有幂等键，未产生新记录。
 */
public record AppendResult(StoredEvent storedEvent, boolean duplicate) {
}
