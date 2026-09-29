package com.github.lisama569.idempotenteventlogjava.store;

/**
 * 追加结果：要么首次接受（Accepted），要么命中幂等键（Duplicate）。
 * 内容冲突在调用方判定，存储层不负责拒绝。
 */
public sealed interface AppendOutcome
        permits AppendOutcome.Accepted, AppendOutcome.Duplicate {

    record Accepted(StoredEvent storedEvent) implements AppendOutcome {
    }

    record Duplicate(IdempotentRecord record) implements AppendOutcome {
    }
}
