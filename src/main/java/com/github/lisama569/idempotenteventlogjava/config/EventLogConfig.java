package com.github.lisama569.idempotenteventlogjava.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 事件日志装配配置。
 */
@Configuration
@EnableConfigurationProperties(EventLogProperties.class)
public class EventLogConfig {
}
