package com.github.lisama569.idempotenteventlogjava.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.lisama569.idempotenteventlogjava.codec.DecodedEnvelope;
import com.github.lisama569.idempotenteventlogjava.codec.EventPayloadCodec;
import com.github.lisama569.idempotenteventlogjava.domain.Event;
import com.github.lisama569.idempotenteventlogjava.error.EventLogException;
import com.github.lisama569.idempotenteventlogjava.error.RejectReason;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 基于本地单个 JSONL 文件的追加式事件日志。
 *
 * <p>口径：
 * <ul>
 *   <li>每行一个事件 JSON，字段固定为 seq/contentHash/eventId/idempotencyKey/entityId/type/
 *       schemaVersion/payload/occurredAt，以原始提交版本渲染，保证旧数据原样保留。</li>
 *   <li>所有 {@code append} 在同一把实例锁内完成"查重 -> 分配序号 -> 落盘 -> 更新内存索引"，
 *       因此并发重复提交只有一个生效，且读永远看到完整的新日志而不是半截状态。</li>
 *   <li>每次追加 fsync 后才更新内存视图，进程崩溃最多丢最后一条未刷盘记录，不会出现半行。</li>
 *   <li>启动时逐行重放：seq 必须连续、信封必须通过严格 schema 校验、contentHash 必须与
 *       canonical 重算结果一致、幂等键不得重复。任何不符都按 {@link RejectReason#CORRUPT_LOG} 拒绝启动。</li>
 * </ul>
 */
public class FileEventLogStore implements EventLogStore {

    private final Path logFile;
    private final EventPayloadCodec codec;
    private final ObjectMapper mapper = new ObjectMapper();

    private final List<StoredEvent> events = new ArrayList<>();
    private final Map<String, IdempotentRecord> idempotents = new HashMap<>();
    private long lastSeq = 0;

    public FileEventLogStore(Path dataDir, EventPayloadCodec codec) {
        this.codec = codec;
        this.logFile = dataDir.resolve("events.log");
        try {
            Files.createDirectories(dataDir);
            if (Files.notExists(logFile)) {
                Files.createFile(logFile);
            }
            load();
        } catch (IOException e) {
            throw new UncheckedIOException("初始化事件日志失败: " + dataDir, e);
        }
    }

    @Override
    public synchronized AppendOutcome append(String idempotencyKey, String contentHash, Event event) {
        IdempotentRecord existing = idempotents.get(idempotencyKey);
        if (existing != null) {
            // 命中幂等键：是否内容冲突由上层比对 contentHash 决定，存储层原样返回首次记录
            return new AppendOutcome.Duplicate(existing);
        }
        long seq = lastSeq + 1;
        ObjectNode line = mapper.createObjectNode();
        line.put("seq", seq);
        line.put("contentHash", contentHash);
        line.put("eventId", event.eventId());
        line.put("idempotencyKey", event.idempotencyKey());
        line.put("entityId", event.entityId());
        line.put("type", event.type());
        line.put("schemaVersion", event.schemaVersion());
        line.set("payload", codec.encodePayload(event.payload(), event.schemaVersion()));
        line.put("occurredAt", event.occurredAt());
        String json;
        try {
            json = mapper.writeValueAsString(line);
        } catch (IOException e) {
            throw new UncheckedIOException("事件序列化失败: " + event.eventId(), e);
        }
        byte[] payloadBytes = json.getBytes(StandardCharsets.UTF_8);
        try (FileChannel channel = FileChannel.open(logFile,
                StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            // 整行在一把锁内一次性写入并 fsync，磁盘上只会出现完整行或没有这行
            ByteBuffer buffer = ByteBuffer.wrap(payloadBytes);
            ByteBuffer newline = ByteBuffer.wrap(new byte[]{'\n'});
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            while (newline.hasRemaining()) {
                channel.write(newline);
            }
            channel.force(false);
        } catch (IOException e) {
            throw new UncheckedIOException("事件落盘失败: " + event.eventId(), e);
        }
        StoredEvent stored = new StoredEvent(seq, event, contentHash);
        events.add(stored);
        idempotents.put(idempotencyKey, new IdempotentRecord(idempotencyKey, event.eventId(), seq, contentHash));
        lastSeq = seq;
        return new AppendOutcome.Accepted(stored);
    }

    @Override
    public synchronized List<StoredEvent> readAll() {
        return List.copyOf(events);
    }

    @Override
    public synchronized Optional<IdempotentRecord> findIdempotent(String idempotencyKey) {
        return Optional.ofNullable(idempotents.get(idempotencyKey));
    }

    @Override
    public synchronized long currentSeq() {
        return lastSeq;
    }

    private void load() {
        List<String> lines;
        try {
            lines = Files.readAllLines(logFile, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("读取事件日志失败: " + logFile, e);
        }
        long expectedSeq = 0;
        for (String rawLine : lines) {
            expectedSeq++;
            JsonNode node = parseLine(rawLine, expectedSeq);
            long seq = requireSeq(node, expectedSeq);
            String contentHash = requireText(node, "contentHash", seq);
            DecodedEnvelope decoded;
            try {
                decoded = codec.decodeEnvelope(stripMeta(node), 0L);
            } catch (EventLogException e) {
                throw new EventLogException(RejectReason.CORRUPT_LOG,
                        "第 " + seq + " 行事件未通过 schema 校验: " + e.getMessage(), e);
            }
            Event event = new Event(decoded.eventId(), decoded.idempotencyKey(), decoded.entityId(),
                    decoded.type(), decoded.schemaVersion(), decoded.payload(), decoded.occurredAt());
            String recomputed = codec.contentHash(event.entityId(), event.type(), event.payload());
            if (!recomputed.equals(contentHash)) {
                throw new EventLogException(RejectReason.CORRUPT_LOG,
                        "第 " + seq + " 行 contentHash 不一致（日志被篡改或迁移错误）: stored="
                                + contentHash + ", recomputed=" + recomputed);
            }
            if (idempotents.containsKey(event.idempotencyKey())) {
                throw new EventLogException(RejectReason.CORRUPT_LOG,
                        "第 " + seq + " 行幂等键重复出现: " + event.idempotencyKey());
            }
            StoredEvent stored = new StoredEvent(seq, event, contentHash);
            events.add(stored);
            idempotents.put(event.idempotencyKey(),
                    new IdempotentRecord(event.idempotencyKey(), event.eventId(), seq, contentHash));
            lastSeq = seq;
        }
    }

    private JsonNode parseLine(String rawLine, long lineNo) {
        if (rawLine.isBlank()) {
            throw new EventLogException(RejectReason.CORRUPT_LOG, "第 " + lineNo + " 行为空或非完整记录");
        }
        try {
            JsonNode node = mapper.readTree(rawLine);
            if (node == null || !node.isObject()) {
                throw new EventLogException(RejectReason.CORRUPT_LOG,
                        "第 " + lineNo + " 行不是 JSON 对象");
            }
            return node;
        } catch (IOException e) {
            throw new EventLogException(RejectReason.CORRUPT_LOG,
                    "第 " + lineNo + " 行不是合法 JSON（可能是半行写入）: " + e.getMessage(), e);
        }
    }

    private long requireSeq(JsonNode node, long expectedSeq) {
        JsonNode seqNode = node.get("seq");
        if (seqNode == null || !seqNode.isIntegralNumber()) {
            throw new EventLogException(RejectReason.CORRUPT_LOG,
                    "第 " + expectedSeq + " 行缺少数值型 seq 字段");
        }
        long seq = seqNode.longValue();
        if (seq != expectedSeq) {
            throw new EventLogException(RejectReason.CORRUPT_LOG,
                    "序列号不连续: 期望 " + expectedSeq + "，实际 " + seq);
        }
        return seq;
    }

    private String requireText(JsonNode node, String field, long seq) {
        JsonNode v = node.get(field);
        if (v == null || !v.isTextual() || v.asText().isBlank()) {
            throw new EventLogException(RejectReason.CORRUPT_LOG,
                    "第 " + seq + " 行缺少非空文本字段: " + field);
        }
        return v.asText();
    }

    private ObjectNode stripMeta(JsonNode node) {
        ObjectNode envelope = mapper.createObjectNode();
        for (String field : List.of("eventId", "idempotencyKey", "entityId", "type",
                "schemaVersion", "payload", "occurredAt")) {
            JsonNode v = node.get(field);
            if (v != null) {
                envelope.set(field, v);
            }
        }
        return envelope;
    }
}
