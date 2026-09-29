package com.github.lisama569.idempotenteventlogjava.error;

/**
 * 失败原因分类，HTTP 层据此映射状态码，测试也据此区分失败原因。
 */
public enum RejectReason {
    /** 同幂等键但内容不一致（冲突）。 */
    IDEMPOTENT_CONFLICT,
    /** 结构/字段/版本不合法：缺字段、多字段、未知版本、类型不匹配等。 */
    SCHEMA_VIOLATION,
    /** 请求体不是合法 JSON。 */
    BAD_JSON,
    /** 参数语义非法（空白 ID、对不存在实体变更等）。 */
    ILLEGAL_ARGUMENT,
    /** 历史节点不存在（实体在该序列号尚未创建）。 */
    STATE_NOT_FOUND,
    /** 请求的快照序列号超出日志范围。 */
    SNAPSHOT_OUT_OF_RANGE,
    /** 持久化日志损坏或违背不变式。 */
    CORRUPT_LOG
}
