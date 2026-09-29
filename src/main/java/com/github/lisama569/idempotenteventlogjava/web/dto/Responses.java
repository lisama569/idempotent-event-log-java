package com.github.lisama569.idempotenteventlogjava.web.dto;

import com.github.lisama569.idempotenteventlogjava.domain.DocumentState;
import com.github.lisama569.idempotenteventlogjava.store.StoredEvent;

import java.util.Map;

/**
 * HTTP 响应 DTO 集合（不可变记录）。
 */
public final class Responses {

    private Responses() {
    }

    public record AppendResponse(
            String status,
            long seq,
            String eventId,
            String idempotencyKey,
            String entityId,
            String type,
            int schemaVersion,
            boolean duplicate
    ) {
        public static AppendResponse accepted(StoredEvent e) {
            return build(e, false);
        }

        public static AppendResponse duplicate(StoredEvent e) {
            return build(e, true);
        }

        private static AppendResponse build(StoredEvent e, boolean duplicate) {
            return new AppendResponse(
                    "OK",
                    e.seq(),
                    e.event().eventId(),
                    e.event().idempotencyKey(),
                    e.event().entityId(),
                    e.event().type(),
                    e.event().schemaVersion(),
                    duplicate);
        }
    }

    public record StateResponse(
            String entityId,
            String title,
            Map<String, String> fields,
            long version,
            boolean deleted,
            long atSeq
    ) {
        public static StateResponse of(DocumentState state, long atSeq) {
            return new StateResponse(state.entityId(), state.title(), state.fields(),
                    state.version(), state.deleted(), atSeq);
        }
    }

    public record ReplayResponse(long currentSeq, long replayedSeq, boolean consistent,
                                 Map<String, DocumentState> replayedStates) {
    }

    public record ErrorResponse(String error, String reason, String message) {
    }
}
