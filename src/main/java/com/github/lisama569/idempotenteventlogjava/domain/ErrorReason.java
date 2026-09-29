package com.github.lisama569.idempotenteventlogjava.domain;

/**
 * 写入失败的明确原因，调用方据此区分错误类别。
 */
public enum ErrorReason {
    /** 同一幂等键已存在，但提交内容与首次不同：明确拒绝。 */
    IDEMPOTENT_CONFLICT,
    /** 载荷结构或取值不合法（缺字段、多字段、坏值、未知类型/版本）。 */
    PAYLOAD_INVALID,
    /** 账户不存在。 */
    ACCOUNT_NOT_FOUND,
    /** 请求参数不合法。 */
    INVALID_PARAMETER,
    /** 业务规则冲突（如重复开户、余额不足）。 */
    BUSINESS_CONFLICT,
    /** 服务端内部错误（如日志损坏）。 */
    INTERNAL_ERROR
}
