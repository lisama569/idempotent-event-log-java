package com.github.lisama569.idempotenteventlogjava.store;

import com.github.lisama569.idempotenteventlogjava.domain.StoredEvent;

import java.util.List;
import java.util.Optional;

/**
 * 追加式事件日志存储。所有读取均为已提交数据的一致快照。
 */
public interface EventStore {

    /** 原子追加：分配序列号并持久化，返回写入后的事件记录。 */
    StoredEvent append(StoredEvent event);

    /** 按幂等键查找已存在事件。 */
    Optional<StoredEvent> findByIdempotencyKey(String key);

    /** 读取全部已提交事件（序列号有序）。 */
    List<StoredEvent> readAll();

    /** 读取序列号不大于 maxSeq 的已提交事件（序列号有序）。 */
    List<StoredEvent> readUpTo(long maxSeq);

    /** 当前已提交事件数（也是最后一条事件的序列号）。 */
    long currentSequence();
}
