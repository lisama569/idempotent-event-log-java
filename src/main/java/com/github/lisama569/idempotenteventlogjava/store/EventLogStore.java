package com.github.lisama569.idempotenteventlogjava.store;

import com.github.lisama569.idempotenteventlogjava.domain.Event;

import java.util.List;
import java.util.Optional;

/**
 * 追加式事件日志存储：只追加、按序列号读取、按幂等键索引。
 */
public interface EventLogStore {

    /** 原子地追加：幂等键已存在则返回其首次记录；否则分配序号落盘。 */
    AppendOutcome append(String idempotencyKey, String contentHash, Event event);

    /** 按序列号顺序读取全部事件。 */
    List<StoredEvent> readAll();

    Optional<IdempotentRecord> findIdempotent(String idempotencyKey);

    long currentSeq();
}
