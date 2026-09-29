package com.github.lisama569.idempotenteventlogjava.config;

import com.github.lisama569.idempotenteventlogjava.codec.EventPayloadCodec;
import com.github.lisama569.idempotenteventlogjava.service.DocumentProjector;
import com.github.lisama569.idempotenteventlogjava.service.EventLogService;
import com.github.lisama569.idempotenteventlogjava.store.EventLogStore;
import com.github.lisama569.idempotenteventlogjava.store.FileEventLogStore;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

/**
 * 装配本地文件事件日志相关 Bean。全部数据保存在 eventlog.data-dir 指向的本机目录。
 */
@Configuration
@EnableConfigurationProperties(EventLogProperties.class)
public class EventLogConfig {

    @Bean
    public EventPayloadCodec eventPayloadCodec() {
        return new EventPayloadCodec();
    }

    @Bean
    public EventLogStore eventLogStore(EventLogProperties properties, EventPayloadCodec codec) {
        return new FileEventLogStore(Path.of(properties.getDataDir()), codec);
    }

    @Bean
    public DocumentProjector documentProjector() {
        return new DocumentProjector();
    }

    @Bean
    public EventLogService eventLogService(EventLogStore store, EventPayloadCodec codec,
                                           DocumentProjector projector) {
        return new EventLogService(store, codec, projector);
    }
}
