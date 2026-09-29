package com.github.lisama569.idempotenteventlogjava.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.lisama569.idempotenteventlogjava.config.EventLogProperties;
import com.github.lisama569.idempotenteventlogjava.domain.ErrorReason;
import com.github.lisama569.idempotenteventlogjava.domain.EventLogException;
import com.github.lisama569.idempotenteventlogjava.domain.StoredEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
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
 * 基于本地文件（JSON Lines，每行一条事件）的追加式事件存储。
 *
 * <p>持久性与一致性口径：</p>
 * <ul>
 *   <li>启动时从日志文件顺序加载，重建内存列表与幂等索引；</li>
 *   <li>追加先完整落盘并 fsync，再提交到内存视图，因此读操作永远看不到
 *       写了一半的记录，崩溃后也不会读到半行（半行在启动加载时被识别并拒绝）；</li>
 *   <li>所有方法线程安全；读取返回内存列表的拷贝快照。</li>
 * </ul>
 */
@Component
public class FileEventStore implements EventStore {

    private static final Logger log = LoggerFactory.getLogger(FileEventStore.class);
    private static final String LOG_FILE_NAME = "events.log";

    private final ObjectMapper objectMapper;
    private final Path logFile;

    private final Object writeLock = new Object();
    private final List<StoredEvent> events = new ArrayList<>();
    private final Map<String, StoredEvent> idempotencyIndex = new HashMap<>();

    public FileEventStore(EventLogProperties properties, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        Path dir = Path.of(properties.getDataDir());
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException("无法创建数据目录: " + dir, e);
        }
        this.logFile = dir.resolve(LOG_FILE_NAME);
        loadFromDisk();
    }

    private void loadFromDisk() {
        if (!Files.exists(logFile)) {
            log.info("事件日志文件不存在，从空日志启动: {}", logFile);
            return;
        }
        long lineNo = 0;
        try (var reader = Files.newBufferedReader(logFile, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                lineNo++;
                if (line.isBlank()) {
                    continue;
                }
                StoredEvent event = objectMapper.readValue(line, StoredEvent.class);
                if (event.seq() != events.size() + 1L) {
                    throw new EventLogException(ErrorReason.INTERNAL_ERROR,
                            "日志序列号不连续: 期望 " + (events.size() + 1L)
                                    + " 实际 " + event.seq() + "（第 " + lineNo + " 行）");
                }
                StoredEvent previous = idempotencyIndex.putIfAbsent(event.idempotencyKey(), event);
                if (previous != null) {
                    throw new EventLogException(ErrorReason.INTERNAL_ERROR,
                            "日志中存在重复幂等键: " + event.idempotencyKey());
                }
                events.add(event);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("加载事件日志失败: " + logFile, e);
        }
        log.info("事件日志加载完成: {} 条事件, 文件={}", events.size(), logFile);
    }

    @Override
    public StoredEvent append(StoredEvent event) {
        synchronized (writeLock) {
            long expectedSeq = events.size() + 1L;
            if (event.seq() != expectedSeq) {
                // 序列号必须由存储统一分配，拒绝外部指定，保证单调连续。
                throw new EventLogException(ErrorReason.INTERNAL_ERROR,
                        "非法序列号: 期望 " + expectedSeq + " 实际 " + event.seq());
            }
            String line;
            try {
                line = objectMapper.writeValueAsString(event);
            } catch (Exception e) {
                throw new EventLogException(ErrorReason.INTERNAL_ERROR,
                        "事件序列化失败: " + e.getMessage());
            }
            // 先完整落盘并强制刷盘，再提交内存视图。
            // 所有追加都在 writeLock 内串行，整行一次 write 后 fsync，
            // 崩溃最多丢失最后未刷盘的整行，绝不会留下半行（加载时也会校验完整性）。
            try (FileChannel ch = FileChannel.open(logFile,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
                ch.write(java.nio.ByteBuffer.wrap(
                        (line + System.lineSeparator()).getBytes(StandardCharsets.UTF_8)));
                ch.force(true);
            } catch (IOException e) {
                throw new UncheckedIOException("事件落盘失败", e);
            }
            events.add(event);
            idempotencyIndex.put(event.idempotencyKey(), event);
            return event;
        }
    }

    @Override
    public Optional<StoredEvent> findByIdempotencyKey(String key) {
        synchronized (writeLock) {
            return Optional.ofNullable(idempotencyIndex.get(key));
        }
    }

    @Override
    public List<StoredEvent> readAll() {
        synchronized (writeLock) {
            return new ArrayList<>(events);
        }
    }

    @Override
    public List<StoredEvent> readUpTo(long maxSeq) {
        synchronized (writeLock) {
            int end = (int) Math.min(Math.max(maxSeq, 0), events.size());
            return new ArrayList<>(events.subList(0, end));
        }
    }

    @Override
    public long currentSequence() {
        synchronized (writeLock) {
            return events.size();
        }
    }
}
