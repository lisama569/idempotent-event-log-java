package com.github.lisama569.idempotenteventlogjava.service;

import com.github.lisama569.idempotenteventlogjava.domain.DocumentState;

import java.util.Map;

/**
 * 从日志重放得到的全部实体状态及重放所到的序列号。
 */
public record ReplayResult(Map<String, DocumentState> states, long replayedSeq) {
}
