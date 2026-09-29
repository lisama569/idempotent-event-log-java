# 本地幂等追加式事件日志（Spring Boot）

一套只依赖本机文件、可通过 HTTP 使用的追加式（append-only）事件日志能力。
数据全部保存在本地目录的单个 JSONL 文件中，**不依赖任何外部数据库/消息服务或真实凭据**。

能力总览：

- 写入按**幂等键去重**：同键同内容只生效一次；同键异内容明确判为冲突；并发重复提交不产生重复记录。
- 基于事件维护**可重建的当前状态**，支持读取任意历史节点（序列号）的快照；实时状态与日志重放结果一致。
- 事件结构支持**版本演进**：缺字段 / 多字段 / 未知版本 / 语义变化都有确定结论，不静默丢弃、不串语义。

---

## 1. HTTP 接口

服务默认随机端口启动（`server.port=0`）。数据目录由 `eventlog.data-dir` 配置，默认 `data/eventlog`。

### 1.1 追加事件 `POST /api/events`

请求体是一个事件信封：

```json
{
  "eventId": "evt-001",
  "idempotencyKey": "order-123#create",
  "entityId": "doc-1",
  "type": "SET_FIELD",
  "schemaVersion": 2,
  "payload": { "field": "greeting", "value": "hello", "transform": "UPPERCASE" }
}
```

信封字段（全部必填，`occurredAt` 可选，缺省由服务端填充 epoch millis 并落盘）：

| 字段 | 含义 |
| --- | --- |
| `eventId` | 客户端给事件的标识（非空） |
| `idempotencyKey` | 幂等键（非空），去重与冲突判定的唯一依据 |
| `entityId` | 实体（文档）ID |
| `type` | `CREATE_DOCUMENT` / `SET_FIELD` / `DELETE_DOCUMENT` |
| `schemaVersion` | 结构版本，当前允许 `1`、`2` |
| `payload` | 与 type/version 对应的载荷对象 |
| `occurredAt` | 可选，非负整数 epoch millis；用于重放可复现 |

响应：

- 首次接受：`200`，`duplicate=false`，`seq` 为新分配的日志序列号（从 1 起单调递增）。
- 同键同内容重复：`200`，`duplicate=true`，返回**首次事件**的 `seq/eventId`，不新增记录、不重复推进状态。
- 同键异内容冲突：`409`，`reason=IDEMPOTENT_CONFLICT`。

```json
{ "status": "OK", "seq": 3, "eventId": "evt-001", "idempotencyKey": "...",
  "entityId": "doc-1", "type": "SET_FIELD", "schemaVersion": 2, "duplicate": false }
```

错误响应统一形如：

```json
{ "error": "idempotent_conflict", "reason": "IDEMPOTENT_CONFLICT", "message": "..." }
```

### 1.2 读当前状态 `GET /api/events/entities/{entityId}`

返回实体当前状态。实体从未创建或已删除：`404 reason=STATE_NOT_FOUND`。

### 1.3 历史快照 `GET /api/events/entities/{entityId}/snapshot?atSeq=N`

返回**重放前 N 条事件（含第 N 条）后**该实体的状态；`atSeq=0` 表示空日志时点。

- `atSeq < 0`：`400 ILLEGAL_ARGUMENT`。
- `atSeq > 当前最大序列号`：`404 SNAPSHOT_OUT_OF_RANGE`（与"实体不存在"区分开）。
- 实体在该时点不存在/已删除：`404 STATE_NOT_FOUND`。

### 1.4 重放校验 `GET /api/events/replay`

从日志全量重放，并与实时维护的状态逐实体、逐字段比对：

```json
{ "currentSeq": 3, "replayedSeq": 3, "consistent": true, "replayedStates": { "...": {} } }
```

若实时状态与重放不一致，服务端抛出 `CORRUPT_LOG`（500），不会静默返回"看起来一致"。

---

## 2. 幂等与冲突规则

### 2.1 内容指纹

每个事件在服务端计算一个 **canonical 内容哈希（SHA-256）**，参与哈希的只有语义内容：

```
entityId | type | canonical payload
```

- 哈希与提交时声明的 `schemaVersion` **无关**。`SET_FIELD` 的 canonical 载荷恒为
  `{field, value, transform}`；v1 数据迁移后 `transform=NONE`。
- 因此「v1 提交 `{field,value}`」与「v2 提交 `{field,value,transform=NONE}`」语义等价、哈希相同，
  用同一个幂等键提交会被识别为重复而不是冲突。
- `eventId`、`occurredAt` 不参与内容哈希（它们不改变事件对状态的语义）。

### 2.2 判定矩阵

| 幂等键 | 内容哈希 | 结论 | HTTP |
| --- | --- | --- | --- |
| 不存在 | — | 首次接受，分配序号落盘 | 200 `duplicate=false` |
| 已存在 | 相同 | 幂等重复，返回首次事件 | 200 `duplicate=true` |
| 已存在 | 不同 | **冲突**，明确拒绝，日志不变 | 409 `IDEMPOTENT_CONFLICT` |

失败原因可区分：冲突是 `IDEMPOTENT_CONFLICT`，结构问题是 `SCHEMA_VIOLATION`，
JSON 语法错误是 `BAD_JSON`，语义非法（空白 ID、非法生命周期）是 `ILLEGAL_ARGUMENT`。

### 2.3 并发口径

所有写操作在服务单把锁内串行完成：
`解码 → 计算哈希 → 查幂等索引 → 生命周期预校验 → 存储原子追加(fsync) → 发布实时状态`。

- N 个线程用同一幂等键并发提交：恰好 1 个首次接受，其余全部识别为重复，日志只有 1 条记录。
- 存储层 `append` 在同一把锁内完成"查重 + 分配序号 + 整行写入 + fsync + 更新内存索引"，
  因此读永远看到写前或写后的完整状态，不会读到写了一半的事件或状态。
- 事件整行一次写入并 `FileChannel.force`，进程崩溃最多是"最后一行没刷盘"，不会留下半行
  （若出现半行，启动时按 `CORRUPT_LOG` 拒绝启动，给出明确结论）。

### 2.4 实体生命周期（写入预校验）

非法生命周期请求在**落盘前**被拒绝（`400 ILLEGAL_ARGUMENT`），日志保持不变：

- 对已存在（未删除）实体再 `CREATE_DOCUMENT`：拒绝。
- 对不存在或已删除实体 `SET_FIELD`：拒绝。
- 对不存在或重复 `DELETE_DOCUMENT`：拒绝。
- 删除后允许重新 `CREATE_DOCUMENT`，视为全新实体（字段清空）。

---

## 3. 重放与快照口径

- **单一折叠函数**：实时状态推进与日志重放共用 `DocumentProjector.apply`（确定性 fold），
  两条路径得到的状态必然一致。
- **确定性**：fold 只依赖事件内容（不读时钟、不依赖外部状态）。给定相同事件前缀，
  重放结果唯一确定、可复现；时间取自落盘的 `occurredAt`，不随重放时刻变化。
- **快照**：`atSeq=N` = 按序列号顺序应用第 1..N 条事件后的状态（含 N）。快照不在磁盘上预存，
  每次从不可变事件列表重放前缀得到，因此任何历史节点都能读取。
- **启动恢复**：进程启动时逐行扫描日志——
  seq 必须连续、信封必须通过与线上相同的严格 schema 校验、内容哈希必须与 canonical 重算一致、
  幂等键不得重复；全部通过后重建事件列表、幂等索引与实时状态。
  任何一项不符都按 `CORRUPT_LOG` 拒绝启动，不会"带病运行"。

文档状态字段：

```json
{ "entityId": "...", "title": "...", "fields": { "a": "..." },
  "version": 3, "deleted": false, "atSeq": 3 }
```

`version` 是最后作用于该实体的事件序列号；`SET_FIELD` 的值落状态前按 `transform`
变换（`NONE/UPPERCASE/LOWERCASE`）。

---

## 4. 结构演进与兼容范围

示例领域是"文档"，事件类型 `CREATE_DOCUMENT` / `SET_FIELD` / `DELETE_DOCUMENT`。

| 类型 | v1 载荷 | v2 变化 |
| --- | --- | --- |
| `CREATE_DOCUMENT` | `{title}` | 无变化，v1/v2 均可 |
| `SET_FIELD` | `{field,value}` | **新增必填** `transform`（`NONE/UPPERCASE/LOWERCASE`） |
| `DELETE_DOCUMENT` | `{}` | 无变化，v1/v2 均可 |

**严格结论（不静默）**：

- **缺字段**：必填字段缺失 → `SCHEMA_VIOLATION`，不补默认值。
  v2 的 `transform` 缺失不会被猜成 `NONE`。
- **多字段**：出现任何未声明字段 → `SCHEMA_VIOLATION`，不静默丢弃。
- **类型不符 / null**：字段 JSON 类型与契约不符 → `SCHEMA_VIOLATION`。
- **未知版本 / 未知事件类型**：`SCHEMA_VIOLATION`，拒绝猜测。
- **未知枚举值**（如 `transform=BOLD`）：`SCHEMA_VIOLATION`，不回退 `NONE`。
- **空白字符串**（结构合法但语义为空）：归 `ILLEGAL_ARGUMENT`，与结构问题区分。

**新旧版本并存的确定语义**：

- v1 `SET_FIELD` 读入时迁移为 canonical 的 `{field,value,transform=NONE}`——
  v1 没有 transform，唯一确定的历史语义就是"原样写入"。旧数据因此能正确还原。
- 磁盘上**保留原始提交版本**的载荷；迁移只发生在读入/重放的 canonical 表示上，
  并由内容哈希校验迁移是否被破坏。
- 把带非 `NONE` transform 的 v2 数据渲染回 v1 会丢失语义，该降级操作被明确拒绝
  （`SCHEMA_VIOLATION`），而不是静默抹掉 transform。
- 兼容范围明确：`schemaVersion` 仅支持 `1`、`2`；超出该范围的版本一律拒绝，需要显式升级。

---

## 5. 存储格式

单个 JSONL 文件：`${eventlog.data-dir}/events.log`，每行一个事件：

```json
{"seq":1,"contentHash":"<sha256 hex>","eventId":"...","idempotencyKey":"...",
 "entityId":"doc-1","type":"SET_FIELD","schemaVersion":2,
 "payload":{"field":"a","value":"hello","transform":"UPPERCASE"},"occurredAt":1727000000000}
```

- 只追加、不修改、不删除历史行；序列号连续。
- 幂等索引是派生数据，启动时由日志重建（日志是唯一事实来源）。

---

## 6. 验证方式

需要 JDK 21+、Maven（Spring Boot 3.4.1）。

```bash
# 全量测试（25 个用例）
mvn test

# 打包并以随机端口启动（默认数据目录 data/eventlog）
mvn -DskipTests package
java -jar target/idempotent-event-log-java-1.0-SNAPSHOT.jar --server.port=0
```

测试覆盖三类重点场景，且每个用例都通过 SLF4J 打印**输入**与**判定依据**（见 surefire 输出）：

| 测试类 | 覆盖内容 |
| --- | --- |
| `EventPayloadCodecTest` | 结构演进：v1→v2 迁移、v1/v2-NONE 等价哈希、缺/多字段、未知版本/枚举、类型错误、降级拒绝 |
| `EventLogServiceTest` | 幂等去重、同键冲突区分原因、**16 线程并发重复提交恰好 1 条记录**、重放与实时一致、任意节点快照、删除/重建、落盘前拒绝非法生命周期 |
| `FileEventLogStoreRecoveryTest` | 新旧版本混存后重启重放一致、幂等索引重建、重放确定性、**篡改 contentHash / 半行 / 日志内 schema 违例 → CORRUPT_LOG** |
| `EventLogHttpIntegrationTest` | HTTP 全链路状态码与 `reason` 分类（200/409/400/404）、快照与重放接口 |

手工快速验证：

```bash
# 写入（注意端口以启动日志 "Tomcat started on port X" 为准）
curl -s -X POST localhost:$PORT/api/events -H 'Content-Type: application/json' -d '{
  "eventId":"e1","idempotencyKey":"k1","entityId":"doc1",
  "type":"CREATE_DOCUMENT","schemaVersion":1,"payload":{"title":"T1"}}'

# 同键重复 -> duplicate:true；改 title 再提交 -> 409 IDEMPOTENT_CONFLICT
curl -s localhost:$PORT/api/events/entities/doc1
curl -s "localhost:$PORT/api/events/entities/doc1/snapshot?atSeq=1"
curl -s localhost:$PORT/api/events/replay
```

---

## 7. 关键类索引

| 类 | 职责 |
| --- | --- |
| `codec/EventPayloadCodec` | 严格 schema 校验、v1→v2 canonical 迁移、canonical 内容哈希 |
| `store/FileEventLogStore` | JSONL 追加、fsync、启动恢复/校验、幂等索引（单锁原子） |
| `service/DocumentProjector` | 事件→状态的确定性 fold（实时与重放共用） |
| `service/EventLogService` | 幂等/冲突判定、写入预校验、实时状态、快照、重放一致性比对 |
| `web/EventLogController` / `GlobalExceptionHandler` | HTTP 接口与原因→状态码映射 |
| `error/RejectReason` | 失败原因分类枚举 |
