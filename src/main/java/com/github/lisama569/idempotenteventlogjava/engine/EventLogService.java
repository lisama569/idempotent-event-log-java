package com.github.lisama569.idempotenteventlogjava.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.lisama569.idempotenteventlogjava.domain.AccountState;
import com.github.lisama569.idempotenteventlogjava.domain.AppendOutcome;
import com.github.lisama569.idempotenteventlogjava.domain.ErrorReason;
import com.github.lisama569.idempotenteventlogjava.domain.EventLogException;
import com.github.lisama569.idempotenteventlogjava.domain.NormalizedEvent;
import com.github.lisama569.idempotenteventlogjava.domain.StoredEvent;
import com.github.lisama569.idempotenteventlogjava.schema.PayloadCodec;
import com.github.lisama569.idempotenteventlogjava.store.EventStore;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 追加式事件日志核心服务。
 *
 * <p>一致性口径：</p>
 * <ul>
 *   <li>写入在单把锁内串行完成「幂等判定 → 业务校验 → 落盘 → 提交实时状态」，
 *       并发重复提交只有一个生效，其余命中去重；</li>
 *   <li>实时状态仅在事件完整落盘后才更新（先存后更新，引用原子替换），
 *       读操作永远看不到写了一半的状态；</li>
 *   <li>实时状态启动时通过与快照查询完全相同的投影逻辑重建，
 *       {@link #replayAndVerify()} 可随时校验二者逐账户一致。</li>
 * </ul>
 */
@Service
public class EventLogService {

    private static final Logger log = LoggerFactory.getLogger(EventLogService.class);

    private final EventStore eventStore;
    private final PayloadCodec payloadCodec;
    private final StateProjector stateProjector;
    private final ObjectMapper objectMapper;

    private final ReentrantLock writeLock = new ReentrantLock();
    private final Map<String, AccountState> liveStates = new HashMap<>();

    public EventLogService(EventStore eventStore, PayloadCodec payloadCodec,
                           StateProjector stateProjector, ObjectMapper objectMapper) {
        this.eventStore = eventStore;
        this.payloadCodec = payloadCodec;
        this.stateProjector = stateProjector;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    void rebuildLiveState() {
        rebuild();
    }

    /** 从日志重建实时状态（非 Spring 装配场景（如单元测试）手动调用）。 */
    public void rebuildForTest() {
        rebuild();
    }

    private void rebuild() {        // 实时状态 = 日志重放结果。启动重建，崩溃恢复后口径不变。
        List<StoredEvent> all = eventStore.readAll();
        for (StoredEvent event : all) {
            NormalizedEvent normalized = payloadCodec.normalize(event);
            AccountState current = liveStates.get(normalized.accountId());
            AccountState next = stateProjector.apply(current, normalized, event.seq());
            liveStates.put(normalized.accountId(), next);
        }
        log.info("实时状态重建完成: {} 个账户, {} 条事件", liveStates.size(), all.size());
    }

    /**
     * 追加事件（幂等）。
     *
     * @return 同键同内容返回既有事件且 deduped=true；新写入返回新事件 deduped=false
     * @throws EventLogException IDEMPOTENT_CONFLICT 同键异内容
     */
    public AppendOutcome append(String idempotencyKey, String eventType,
                                Integer schemaVersion, JsonNode payload) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new EventLogException(ErrorReason.INVALID_PARAMETER, "idempotencyKey 不能为空");
        }
        // 解析（结构校验 + 归一化 + 指纹）在锁外完成，缩短临界区；
        // 结构非法的请求不占用序列号、不产生任何记录。
        PayloadCodec.ParsedPayload parsed = payloadCodec.parse(eventType, schemaVersion, payload);

        writeLock.lock();
        try {
            StoredEvent existing = eventStore.findByIdempotencyKey(idempotencyKey).orElse(null);
            if (existing != null) {
                if (existing.canonicalContent().equals(parsed.canonicalContent())) {
                    log.info("幂等命中: key={}, seq={}, 返回首次结果，不产生新记录",
                            idempotencyKey, existing.seq());
                    return new AppendOutcome(existing, true);
                }
                throw new EventLogException(ErrorReason.IDEMPOTENT_CONFLICT,
                        "幂等键 " + idempotencyKey + " 已用于不同内容的事件 seq=" + existing.seq()
                                + "；首次指纹=" + existing.canonicalContent()
                                + "，本次指纹=" + parsed.canonicalContent());
            }

            long seq = eventStore.currentSequence() + 1L;
            String rawPayloadJson = writePayload(payload);
            StoredEvent event = new StoredEvent(seq, idempotencyKey, eventType,
                    schemaVersion == null ? 1 : schemaVersion,
                    rawPayloadJson, parsed.canonicalContent(), System.currentTimeMillis());

            // 业务校验与落盘使用同一条 apply 规则，保证实时状态与重放必然一致。
            AccountState current = liveStates.get(parsed.normalized().accountId());
            AccountState next = stateProjector.apply(current, parsed.normalized(), seq);

            eventStore.append(event);
            liveStates.put(parsed.normalized().accountId(), next);
            log.info("事件已追加: seq={}, key={}, type={}, v{}, account={}, amountCents={}",
                    seq, idempotencyKey, eventType, event.schemaVersion(),
                    parsed.normalized().accountId(), parsed.normalized().amountCents());
            return new AppendOutcome(event, false);
        } finally {
            writeLock.unlock();
        }
    }

    /** 读取账户当前状态（实时视图）。 */
    public Optional<AccountState> getCurrentState(String accountId) {
        writeLock.lock();
        try {
            return Optional.ofNullable(liveStates.get(accountId));
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * 读取截至指定序列号时的历史快照。
     * atSequence 取 [0, 当前序列号]；0 表示空日志（无账户）。
     */
    public Optional<AccountState> getSnapshot(String accountId, long atSequence) {
        long current = eventStore.currentSequence();
        if (atSequence < 0 || atSequence > current) {
            throw new EventLogException(ErrorReason.INVALID_PARAMETER,
                    "atSequence 必须在 [0," + current + "] 内，实际=" + atSequence);
        }
        List<StoredEvent> prefix = eventStore.readUpTo(atSequence);
        return stateProjector.project(prefix, accountId);
    }

    /**
     * 全量重放日志，逐账户与实时状态比对。
     * 同样的日志永远重放出同样的结果（纯函数投影）。
     */
    public ReplayVerification replayAndVerify() {
        List<StoredEvent> all = eventStore.readAll();
        java.util.Set<String> accountIds = new java.util.TreeSet<>();
        Map<String, AccountState> replayed = new HashMap<>();
        for (StoredEvent event : all) {
            NormalizedEvent normalized = payloadCodec.normalize(event);
            accountIds.add(normalized.accountId());
            AccountState cur = replayed.get(normalized.accountId());
            replayed.put(normalized.accountId(), stateProjector.apply(cur, normalized, event.seq()));
        }

        writeLock.lock();
        try {
            for (String id : accountIds) {
                AccountState live = liveStates.get(id);
                AccountState replay = replayed.get(id);
                if (!java.util.Objects.equals(live, replay)) {
                    log.error("重放不一致: account={}, live={}, replay={}", id, live, replay);
                    return new ReplayVerification(all.size(), false, accountIds.size());
                }
            }
            if (liveStates.size() != accountIds.size()) {
                log.error("重放不一致: 账户数量不同 live={}, replay={}",
                        liveStates.size(), accountIds.size());
                return new ReplayVerification(all.size(), false, accountIds.size());
            }
        } finally {
            writeLock.unlock();
        }
        log.info("重放校验通过: {} 条事件, {} 个账户", all.size(), accountIds.size());
        return new ReplayVerification(all.size(), true, accountIds.size());
    }

    /** 读取事件日志（可选截至序列号）。 */
    public List<StoredEvent> readLog(Long atSequence) {
        if (atSequence == null) {
            return eventStore.readAll();
        }
        long current = eventStore.currentSequence();
        if (atSequence < 0 || atSequence > current) {
            throw new EventLogException(ErrorReason.INVALID_PARAMETER,
                    "atSequence 必须在 [0," + current + "] 内，实际=" + atSequence);
        }
        return eventStore.readUpTo(atSequence);
    }

    private String writePayload(JsonNode payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new EventLogException(ErrorReason.PAYLOAD_INVALID,
                    "payload 无法序列化为 JSON: " + e.getMessage());
        }
    }

    /** 全量重放校验结果。 */
    public record ReplayVerification(long eventCount, boolean consistent, long checkedAccounts) {
    }
}
