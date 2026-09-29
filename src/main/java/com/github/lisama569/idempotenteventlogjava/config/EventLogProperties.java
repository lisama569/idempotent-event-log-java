package com.github.lisama569.idempotenteventlogjava.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * eventlog.* 配置。
 */
@ConfigurationProperties(prefix = "eventlog")
public class EventLogProperties {

    /** JSONL 日志文件所在本地目录。 */
    private String dataDir = "data/eventlog";

    public String getDataDir() {
        return dataDir;
    }

    public void setDataDir(String dataDir) {
        this.dataDir = dataDir;
    }
}
