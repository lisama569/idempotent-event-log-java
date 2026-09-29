package com.github.lisama569.idempotenteventlogjava.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 本地事件日志配置。
 */
@ConfigurationProperties(prefix = "eventlog")
public class EventLogProperties {

    /** 事件日志文件目录（本机文件系统）。 */
    private String dataDir = "./data/eventlog";

    public String getDataDir() {
        return dataDir;
    }

    public void setDataDir(String dataDir) {
        this.dataDir = dataDir;
    }
}
