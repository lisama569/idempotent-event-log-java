package com.github.lisama569.idempotenteventlogjava.service;

import com.github.lisama569.idempotenteventlogjava.domain.DocumentState;
import com.github.lisama569.idempotenteventlogjava.domain.EventPayload;
import com.github.lisama569.idempotenteventlogjava.error.EventLogException;
import com.github.lisama569.idempotenteventlogjava.error.RejectReason;
import com.github.lisama569.idempotenteventlogjava.store.StoredEvent;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 事件 -> 文档状态的确定性折叠（fold）。
 *
 * <p>实时维护与从日志重放使用<b>同一个</b> {@link #apply}，因此两条路径得到的状态必然一致；
 * 给定相同的事件前缀，重放结果唯一确定（不依赖时钟或外部状态）。
 *
 * <p>实体生命周期不变式（违例一律 CORRUPT_LOG，给出明确结论，不做猜测）：
 * <ul>
 *   <li>CREATE_DOCUMENT：实体必须不存在（或已删除，删除后重建视为全新实体，字段清空）。</li>
 *   <li>SET_FIELD：实体必须存在且未删除。</li>
 *   <li>DELETE_DOCUMENT：实体必须存在且未删除。</li>
 * </ul>
 */
public class DocumentProjector {

    public DocumentProjector() {
    }

    public DocumentState apply(DocumentState state, StoredEvent stored) {
        String entityId = stored.event().entityId();
        long seq = stored.seq();
        EventPayload payload = stored.event().payload();
        return switch (payload) {
            case EventPayload.CreateDocument c -> {
                if (state != null && !state.deleted()) {
                    throw new EventLogException(RejectReason.CORRUPT_LOG,
                            "事件 seq=" + seq + " 试图重复 CREATE_DOCUMENT 现存实体 " + entityId);
                }
                yield new DocumentState(entityId, c.title(), new LinkedHashMap<>(), seq, false);
            }
            case EventPayload.SetField s -> {
                if (state == null) {
                    throw new EventLogException(RejectReason.CORRUPT_LOG,
                            "事件 seq=" + seq + " 对尚未创建的实体 " + entityId + " 执行 SET_FIELD");
                }
                if (state.deleted()) {
                    throw new EventLogException(RejectReason.CORRUPT_LOG,
                            "事件 seq=" + seq + " 对已删除实体 " + entityId + " 执行 SET_FIELD");
                }
                Map<String, String> fields = new LinkedHashMap<>(state.fields());
                fields.put(s.field(), s.transform().apply(s.value()));
                yield new DocumentState(entityId, state.title(), fields, seq, false);
            }
            case EventPayload.DeleteDocument d -> {
                if (state == null) {
                    throw new EventLogException(RejectReason.CORRUPT_LOG,
                            "事件 seq=" + seq + " 删除尚未创建的实体 " + entityId);
                }
                if (state.deleted()) {
                    throw new EventLogException(RejectReason.CORRUPT_LOG,
                            "事件 seq=" + seq + " 重复删除实体 " + entityId);
                }
                yield new DocumentState(entityId, state.title(), state.fields(), seq, true);
            }
        };
    }

    /** 从空状态重放至第 endSeq 个事件（含）。events 按 seq 升序，不依赖其内部序号以外的状态。 */
    public Map<String, DocumentState> replay(List<StoredEvent> events, long endSeq) {
        Map<String, DocumentState> states = new LinkedHashMap<>();
        for (StoredEvent e : events) {
            if (e.seq() > endSeq) {
                break;
            }
            String id = e.event().entityId();
            states.put(id, apply(states.get(id), e));
        }
        return states;
    }

    public ReplayResult replayAll(List<StoredEvent> events) {
        long maxSeq = events.isEmpty() ? 0L : events.get(events.size() - 1).seq();
        return new ReplayResult(replay(events, maxSeq), maxSeq);
    }
}
