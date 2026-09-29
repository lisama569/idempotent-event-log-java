package com.github.lisama569.idempotenteventlogjava.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.lisama569.idempotenteventlogjava.codec.DecodedEnvelope;
import com.github.lisama569.idempotenteventlogjava.codec.EventPayloadCodec;
import com.github.lisama569.idempotenteventlogjava.domain.DocumentState;
import com.github.lisama569.idempotenteventlogjava.domain.Event;
import com.github.lisama569.idempotenteventlogjava.error.EventLogException;
import com.github.lisama569.idempotenteventlogjava.error.RejectReason;
import com.github.lisama569.idempotenteventlogjava.store.AppendOutcome;
import com.github.lisama569.idempotenteventlogjava.store.EventLogStore;
import com.github.lisama569.idempotenteventlogjava.store.StoredEvent;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 对外门面：写幂等与冲突、实时状态、历史快照、重放一致性校验。
 *
 * <p>并发口径：所有写操作在 {@code this} 单把锁内完成
 * "解码 -> 内容哈希 -> 存储原子追加 -> 实时状态推进"。因此并发重复提交要么是
 * 首次接受、要么命中幂等返回原事件，绝不会产生两条记录；读实时状态时要么看到写前、
 * 要么看到写后，不会看到写了一半的状态。
 */
public class EventLogService {

    private final EventLogStore store;
    private final EventPayloadCodec codec;
    private final DocumentProjector projector;
    private final ObjectMapper mapper = new ObjectMapper();

    private final Map<String, DocumentState> liveStates = new LinkedHashMap<>();

    public EventLogService(EventLogStore store, EventPayloadCodec codec, DocumentProjector projector) {
        this.store = store;
        this.codec = codec;
        this.projector = projector;
        restore();
    }

    /**
     * 追加事件。
     * <ul>
     *   <li>幂等键首次出现：校验实体生命周期不变式后落盘并推进实时状态。</li>
     *   <li>幂等键已存在且内容哈希相同：返回首次事件（duplicate），不产生新记录。</li>
     *   <li>幂等键已存在但内容哈希不同：拒绝 {@link RejectReason#IDEMPOTENT_CONFLICT}。</li>
     * </ul>
     */
    public synchronized AppendResult append(JsonNode requestJson) {
        DecodedEnvelope decoded = codec.decodeEnvelope(requestJson, System.currentTimeMillis());
        String hash = codec.contentHash(decoded.entityId(), decoded.type(), decoded.payload());
        String key = decoded.idempotencyKey();
        // 服务锁内先查幂等索引，保证"查重/冲突判定/预校验/落盘/发布"对调用方是一次原子步骤
        var existing = store.findIdempotent(key);
        if (existing.isPresent()) {
            var record = existing.get();
            if (!record.contentHash().equals(hash)) {
                throw new EventLogException(RejectReason.IDEMPOTENT_CONFLICT,
                        "幂等键 '" + key + "' 已用于内容不同的事件（seq=" + record.seq()
                                + "）；相同键必须提交相同内容。storedHash=" + record.contentHash()
                                + ", requestHash=" + hash);
            }
            // 内容一致：返回首次接受的那条，不产生新记录、不重复推进状态
            return new AppendResult(store.readAll().get((int) (record.seq() - 1)), true);
        }
        long nextSeq = store.currentSeq() + 1;
        Event event = new Event(decoded.eventId(), key, decoded.entityId(),
                decoded.type(), decoded.schemaVersion(), decoded.payload(), decoded.occurredAt());
        // 落盘前先做生命周期不变式预校验，非法请求被拒绝且日志保持不变
        StoredEvent candidate = new StoredEvent(nextSeq, event, hash);
        DocumentState projected;
        try {
            projected = projector.apply(liveStates.get(event.entityId()), candidate);
        } catch (EventLogException e) {
            if (e.reason() == RejectReason.CORRUPT_LOG) {
                throw new EventLogException(RejectReason.ILLEGAL_ARGUMENT,
                        "事件不满足实体生命周期规则，已拒绝且未写入日志: " + e.getMessage(), e);
            }
            throw e;
        }
        AppendOutcome outcome = store.append(key, hash, event);
        StoredEvent stored = switch (outcome) {
            // 服务锁是唯一写入口，findIdempotent 已确认不存在，这里必为 Accepted
            case AppendOutcome.Accepted a -> a.storedEvent();
            case AppendOutcome.Duplicate d -> throw new EventLogException(RejectReason.CORRUPT_LOG,
                    "写入阶段意外命中幂等键: " + d.record().idempotencyKey());
        };
        // 投影已预校验通过，此处发布实时状态；读到的永远是写前或写后的完整状态
        liveStates.put(stored.event().entityId(), projected);
        return new AppendResult(stored, false);
    }

    /** 当前状态；实体从未创建或已删除都给明确结论（404/STATE_NOT_FOUND）。 */
    public synchronized DocumentState currentState(String entityId) {
        DocumentState state = liveStates.get(entityId);
        if (state == null || state.deleted()) {
            throw new EventLogException(RejectReason.STATE_NOT_FOUND,
                    "实体当前不存在或已删除: " + entityId);
        }
        return state;
    }

    /**
     * 历史快照：重放日志前 atSeq 条事件（含 atSeq）后读取实体状态。
     * atSeq=0 表示空日志时点。
     */
    public DocumentState snapshotAt(String entityId, long atSeq) {
        if (atSeq < 0) {
            throw new EventLogException(RejectReason.ILLEGAL_ARGUMENT, "atSeq 不能为负数: " + atSeq);
        }
        List<StoredEvent> all;
        synchronized (this) {
            if (atSeq > store.currentSeq()) {
                throw new EventLogException(RejectReason.SNAPSHOT_OUT_OF_RANGE,
                        "atSeq=" + atSeq + " 超出当前日志范围，最大序列号: " + store.currentSeq());
            }
            all = store.readAll();
        }
        // 重放在锁外进行：读的是不可变事件快照，结果对给定事件前缀完全确定
        Map<String, DocumentState> states = projector.replay(all, atSeq);
        DocumentState state = states.get(entityId);
        if (state == null || state.deleted()) {
            throw new EventLogException(RejectReason.STATE_NOT_FOUND,
                    "实体在 atSeq=" + atSeq + " 时不存在或已删除: " + entityId);
        }
        return state;
    }

    /** 从日志全量重放并与实时状态逐实体、逐字段比对。 */
    public synchronized ReplayResult verifyReplay() {
        List<StoredEvent> all = store.readAll();
        ReplayResult replayed = projector.replayAll(all);
        compareStates(liveStates, replayed.states());
        return replayed;
    }

    public synchronized List<StoredEvent> events() {
        return store.readAll();
    }

    public synchronized long currentSeq() {
        return store.currentSeq();
    }

    /** 解析 HTTP 原始请求体（JSON 语法错误归类 BAD_JSON，与 schema 问题区分开）。 */
    public AppendResult appendRaw(String rawJson) {
        JsonNode node;
        try {
            node = mapper.readTree(rawJson == null ? "" : rawJson);
        } catch (Exception e) {
            throw new EventLogException(RejectReason.BAD_JSON, "请求体不是合法 JSON: " + e.getMessage(), e);
        }
        return append(node);
    }

    private void restore() {
        ReplayResult replayed = projector.replayAll(store.readAll());
        liveStates.putAll(replayed.states());
    }

    private void compareStates(Map<String, DocumentState> live, Map<String, DocumentState> replayed) {
        if (!live.keySet().equals(replayed.keySet())) {
            throw new EventLogException(RejectReason.CORRUPT_LOG,
                    "实时状态与重放状态的实体集合不一致: live=" + live.keySet()
                            + ", replayed=" + replayed.keySet());
        }
        for (Map.Entry<String, DocumentState> entry : live.entrySet()) {
            DocumentState a = entry.getValue();
            DocumentState b = replayed.get(entry.getKey());
            if (!a.equals(b)) {
                throw new EventLogException(RejectReason.CORRUPT_LOG,
                        "实体 " + entry.getKey() + " 的实时状态与重放状态不一致: live="
                                + a + ", replayed=" + b);
            }
        }
    }
}
