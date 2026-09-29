package com.github.lisama569.idempotenteventlogjava.domain;

/**
 * 一次追加请求的处理结果。
 *
 * @param event    生效的事件记录
 * @param deduped  true 表示命中幂等去重（本次未产生新记录）
 */
public record AppendOutcome(StoredEvent event, boolean deduped) {
}
