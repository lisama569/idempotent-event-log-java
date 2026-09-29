# 本地追加式事件日志（幂等写入 + 可重放状态 + 结构演进）

一个纯本机的 Spring Boot 服务：事件只能追加、不能修改；写入按幂等键去重；
当前状态由事件重放得到，可读取任意历史节点的快照；事件载荷支持多版本结构并存。
**不依赖任何外部服务（无数据库、无消息队列、无需真实凭据）**，数据只落在本机文件。

## 运行与验证

```bash
mvn spring-boot:run          # 启动（随机端口，见 server.port=0）
mvn test                     # 全部测试（13 个）
```

测试日志中会打印每次输入与判定依据（生效 / 去重 / 冲突 / 非法、归一化金额、快照比对结果等）。

## HTTP 接口

所有请求/响应均为 JSON，服务端口默认随机（`server.port=0`），可自行改为固定端口。

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/events` | 追加事件。**201** 新写入；**200** 命中幂等去重（响应体 `deduped=true`） |
| GET | `/api/accounts/{accountId}` | 账户当前状态 |
| GET | `/api/accounts/{accountId}/snapshot?atSequence=N` | 截至第 N 条事件时的历史快照 |
| GET | `/api/events?atSequence=N` | 读取事件日志（可只读到第 N 条） |
| GET | `/api/admin/replay` | 全量重放并校验与实时状态一致 |

写入请求体：

```json
{
  "idempotencyKey": "biz-tx-001",
  "eventType": "DEPOSIT",
  "schemaVersion": 2,
  "payload": "{\"accountId\":\"acct-1\",\"amountCents\":1000}"
}
```

`payload` 是 JSON 对象的字符串形式；`schemaVersion` 缺省按 **1** 处理。

错误响应统一为 `{"error":true,"reason":"...","message":"..."}`，可按 `reason` 区分：

| reason | HTTP 状态码 | 含义 |
|---|---|---|
| `IDEMPOTENT_CONFLICT` | 409 | 幂等键已被**不同内容**的事件使用，明确拒绝 |
| `BUSINESS_CONFLICT` | 409 | 业务规则冲突（重复开户、未开户即交易、余额不足） |
| `PAYLOAD_INVALID` | 400 | 载荷结构/取值非法：缺字段、多字段、坏值、未知事件类型、未知版本 |
| `INVALID_PARAMETER` | 400 | 请求参数非法（幂等键为空、快照序号越界等） |
| `ACCOUNT_NOT_FOUND` | 404 | 账户（在该历史节点）不存在 |
| `INTERNAL_ERROR` | 500 | 服务端内部错误（如本地日志文件损坏） |

## 幂等与冲突规则

1. 每条写入必须带非空 `idempotencyKey`。
2. 同一键**第一次**提交：校验通过后分配下一个连续序列号，整行落盘并 fsync 后生效。
3. 同一键**再次**提交：
   - 内容指纹相同 → **去重**，直接返回第一次的事件记录（含原序列号），不产生新记录、状态不变；
   - 内容指纹不同 → 以 `IDEMPOTENT_CONFLICT`（409）**明确拒绝**，不会“后写覆盖”。
4. 指纹基于**归一化后的确定语义**：`eventType + accountId + amountCents`，
   与提交版本、字段写法无关。因此 v1 的 `"10.00"` 元 与 v2 的 `10` 分
   虽字面接近，但归一化后分别是 1000 分和 10 分，同键提交必判冲突——避免语义串错。
5. 并发重复提交：写入在单把锁内串行执行
   “幂等判定 → 业务校验 → 分配序列号 → 落盘 → 提交状态”，
   因此同一键的 N 个并发请求**恰好一个生效**，其余全部去重；
   已用键的异内容请求在锁内也一定能观察到首次记录而被拒绝。
   （见 `IdempotencyConcurrencyTest`，16 线程 × 5 次同一键。）

## 重放与快照口径

- 事件以 JSON Lines 追加存于 `${eventlog.data-dir}/events.log`（默认 `./data/eventlog`），
  每行一条，字段含：序列号、幂等键、事件类型、结构版本、**原始载荷原文**、内容指纹、写入时间。
- 实时状态**本身就是事件重放的结果**：启动时从日志顺序投影重建；
  每次写入也走同一条纯函数 `apply` 规则更新内存状态。
  因此“实时维护的状态”和“从日志全量重放的状态”必然一致，
  `GET /api/admin/replay` 逐账户比对并返回 `consistent`。
- 历史快照：`atSequence=N` 表示“只应用前 N 条事件后的状态”，
  即当时（第 N 条事件提交之后）读到的口径；`N=0` 为空日志。
  快照由独立前缀重放得到，不依赖实时状态，结果可复现。
- **读不到写一半的状态**：事件先整行落盘并 fsync，再更新内存状态；
  读操作拿内存引用（`HashMap` 中整条 record 引用替换），要么看到写入前、要么看到写入后。
  进程崩溃最多丢失最后一条未刷盘的整行，绝不会残留半行（启动加载时还会校验
  序列号连续与幂等键唯一，发现损坏直接以 `INTERNAL_ERROR` 拒绝启动，不静默处理）。
- 投影规则（确定性）：`ACCOUNT_OPENED` 要求账户不存在；
  `DEPOSIT/WITHDRAWAL` 要求账户已开立；取款后余额不得为负。金额内部统一为整数“分”。

## 结构演进与兼容范围

当前定义了两个载荷版本，新旧版本可在同一日志中并存，重放时按各自版本还原：

- **v1**（`schemaVersion=1`，缺省值）：
  `DEPOSIT/WITHDRAWAL` 载荷为 `{"accountId":"...","amountYuan":"12.34"}`，
  金额为**最多两位小数的十进制字符串**，单位元；按固定汇率 `1 元 = 100 分` 归一化。
- **v2**（`schemaVersion=2`）：
  `DEPOSIT/WITHDRAWAL` 载荷为 `{"accountId":"...","amountCents":1234}`，金额为非负整数分。
- `ACCOUNT_OPENED` 两个版本均只有 `{"accountId":"..."}`。
- 事件类型：`ACCOUNT_OPENED | DEPOSIT | WITHDRAWAL`。

确定结论原则（**不静默丢弃、不猜测**）：

- 缺必填字段、出现未知（多余）字段、字段类型不符 → `PAYLOAD_INVALID` 拒绝；
  例如 v2 请求里写 v1 的 `amountYuan` 会因“未知字段”被拒绝，而不是被当成别的含义。
- v1 金额只接受字符串（拒绝浮点数字面量，避免二进制浮点误差）；
  超过两位小数、非法数字、负数一律拒绝，不做隐式舍入。
- 未知 `schemaVersion`、未知 `eventType` 一律拒绝。
- 原始载荷原文持久化，重放走与写入完全相同的解析路径，旧数据语义可复现。

新版本的扩展方式：在 `VersionedPayloadCodec` 中注册新版本的字段集合、校验规则与
到“分”的归一化规则即可；未注册的版本永远得到确定的拒绝结论。

## 测试覆盖

- `IdempotencyConcurrencyTest`：并发重复写入恰好生效一次；同键异内容为
  `IDEMPOTENT_CONFLICT`；非法载荷为 `PAYLOAD_INVALID`；业务冲突为 `BUSINESS_CONFLICT`。
- `ReplayConsistencyTest`：实时状态 = 全量重放；逐个历史节点快照 = 前缀重放；
  “重启”后从日志重建状态一致；快照序号越界校验；不相关账户不串号。
- `SchemaEvolutionTest`：v1/v2 并存且归一化确定；缺字段/多字段/坏值/未知版本拒绝；
  同键跨版本语义不同判冲突。
- `HttpApiIntegrationTest`：HTTP 全流程与按原因区分的状态码/响应体。
