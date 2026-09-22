# 本地生物数据库记录聚合与跨源标识符解析服务

一个纯本地、零外部依赖网络的 Spring Boot 服务：用**本地快照文件**模拟多个生物数据库
（RefSeq / UniProt / Ensembl 风格）的数据导出，完成多来源归一化导入、跨源标识符解析、
字段冲突的确定性裁决，以及快照版本切换/回退，并保证并发查询始终看到**单一版本的完整
视图**。

- 不访问外部网络、真实数据库或任何凭据；
- 仅依赖工程已有依赖（spring-boot-starter-web / starter-test，含 Jackson、SLF4J、JUnit 5）；
- 所有数据来自文本快照（JSON / CSV / FLAT），通过 REST `text/plain` 或直接调用服务导入。

---

## 1. 支持的来源格式

三种格式都表达同一组字段：`id, type, symbol, name, sequence, organism, xrefs`。
其中 `id` 为来源内局部标识，`type` 为 `GENE` 或 `PROTEIN`，`symbol` 为必需字段；
`xrefs` 为跨源标识引用（`命名空间:标识`，多个用 `;` 分隔）。

### 1.1 JSON（`format=JSON`）
JSON 数组，每个元素是一条记录对象：

```json
[
  {"id": "NM_000518", "type": "gene", "symbol": "HBB",
   "name": "hemoglobin subunit beta", "organism": "Homo sapiens",
   "xrefs": {"ensembl": "ENSG00000244734"}}
]
```

- 根不是数组、非法 JSON、元素不是对象、标量字段不是字符串、`xrefs` 不是对象
  → `MALFORMED_SYNTAX`。

### 1.2 CSV（`format=CSV`）
首行（忽略 `#` 注释行与空行）必须是表头：

```csv
id,type,symbol,name,sequence,organism,xrefs
P68871,protein,HBB_HUMAN,Hemoglobin subunit beta,MVHLTPEEKSAVTAL,Homo sapiens,refseq:NP_00517
```

- 支持双引号字段与 `""` 转义；列数不符、未闭合引号、表头错误、`xrefs` 里的坏配对
  → `MALFORMED_SYNTAX`（错误信息带行号，原因可区分）。

### 1.3 管道分隔 FLAT（`format=FLAT`）

```
REC|id|type|symbol|name|sequence|organism|ns:val;ns:val
```

- 每行必须以 `REC` 开头且恰好 8 个字段，否则 `MALFORMED_SYNTAX`；
- `#` 注释行、空行被忽略；完全没有数据行也算语法错误。

### 1.4 拒绝与冲突原因（可区分，绝不静默丢弃）

| 原因 `RejectionReason` | 含义 |
|---|---|
| `MALFORMED_SYNTAX` | 格式无法解析（坏 JSON、列数不符、坏表头、坏引号、坏 xref 配对等） |
| `MISSING_REQUIRED_FIELD` | 缺少必填字段（`id` / `type` / `symbol`） |
| `UNKNOWN_ENTITY_TYPE` | `type` 不是 `GENE`/`PROTEIN` |
| `INVALID_IDENTIFIER` | 标识含非法字符（仅允许 `[A-Za-z0-9._-]`） |
| `DUPLICATE_SOURCE_IDENTIFIER` | **同一来源内**局部 id 重复（保留首次出现，重复项进入冲突列表） |

导入结果 `ImportReport` 同时给出 `accepted`、`rejected`（拒绝）与 `conflicts`
（来源内冲突），每条都带原始片段、原因与细节（含行号）。

---

## 2. 跨源标识符解析与优先级

解析由 `PriorityIdentifierResolver` 实现，结论带 **rulesVersion**（当前
`priority-rules-v1`）。规则改变会升级该版本号，历史快照配合历史规则版本即可复现旧结论。

### 2.1 合并依据（等价关系）
- 每条记录拥有其“本来源身份 token”：`source:localId`。
- 记录通过**显式 xref** 引用 `namespace:value`：
  - 若同类型记录拥有该 token（其 `source==namespace` 且 `localId==value`）或断言同一 token，
    二者合并；
  - 连接用并查集计算，满足传递性，且与记录到达顺序无关。
- 基因与蛋白**永不跨类型合并**。

### 2.2 不静默处理的歧义（`AmbiguousLink`）
- `DANGLING_REFERENCE`：xref 指向任何记录都不拥有的 token（悬挂引用），不断言合并；
- `CROSS_TYPE_COLLISION`：同一 token 被不同类型实体声明（如某蛋白引用了某基因的 id）。

两者都写入 `ResolutionReport.ambiguities`，给出 token、owner 与 claimer，绝不静默取其一。

### 2.3 字段裁决优先级
对每个合并后实体，`symbol/name/sequence/organism` 取**最高优先级来源提供的第一个非空值**。
默认来源优先级（高 → 低）：

```
refseq > uniprot > ensembl > genbank > ncbi
```

- 未在列表中的来源按名称升序排在已知来源之后；
- 可用配置覆盖：`aggregator.source-priority=srcA,srcB,...`。

只要更低优先级来源给出了**不同的非空值**，就生成一条 `ResolutionConflict`，记录
`field / winnerSource / winnerValue / loserSources / loserValues`（loser 按来源优先级排序），
冲突被显式保留而不是被吞掉。

- 实体键：`TYPE:最高优先级来源:localId`（同来源并列按 localId 升序）。
- 实体、成员、歧义、map 迭代全部全序化，同一快照永远产生字节级一致的结论。

---

## 3. 快照版本、幂等与并发一致性语义

### 3.1 版本模型
- **版本（snapshot version）** 是一次逻辑数据发布，由一个或多个**来源导入**聚合而成。
- 同一 `(version, source)`：
  - 再次导入**内容完全相同**（对 `version|source|format|内容` 取 SHA-256）→ `DUPLICATE`，
    **幂等**：不新增记录、计数不漂移；
  - 再次导入**内容不同** → `CONFLICTING_REIMPORT`（HTTP 409），已发布版本不可被静默覆盖。
- 首次导入的版本成为初始 current；之后只有显式 `switch` 才会移动 current 指针。

### 3.2 切换 / 回退
- `POST /api/snapshots/{version}/switch` 原子切换 current；历史版本完整保留，可随时回退，
  回退后结论与当时完全一致（数据不可变 + 规则版本盖章）。
- 切换到未知版本返回 404。

### 3.3 并发一致性（copy-on-publish + 不可变视图）
- 所有变更在同一把锁内完成，并以**一次**向 `volatile`/`ConcurrentHashMap` 发布一个
  完整不可变 `SnapshotView` 结束；
- 查询（`queryAll` / `resolveIdentifier` / 取 snapshot/resolution）先**捕获一个视图**，
  随后整个查询只针对该视图；
- 因此刷新或回退进行中时，进行中的查询要么看到完整旧版、要么看到完整新版，
  **绝不会看到新旧混合或缺半截记录的视图**。
- 并发首次导入用 `putIfAbsent` 原子认领 `(version, source)`，避免互相覆盖；
  并发相同导入恰有 1 个 `CREATED`，其余全部 `DUPLICATE`。

---

## 4. REST API

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/snapshots/{version}/imports?source=&format=JSON\|CSV\|FLAT` | 以 `text/plain` 导入一个来源；200 `CREATED`/`DUPLICATE`，409 `CONFLICTING_REIMPORT` |
| POST | `/api/snapshots/{version}/switch` | 原子切换 current；未知版本 404 |
| GET  | `/api/snapshots` | 版本列表（发布顺序）与 current |
| GET  | `/api/snapshots/{version}/snapshot` | 该版本归一化记录、拒绝、来源内冲突 |
| GET  | `/api/snapshots/{version}/resolution` | 跨源解析结论（实体、字段冲突、歧义、规则版本） |
| GET  | `/api/entities?identifier=` | 在 current 视图按 local id / `ns:id` / 实体键解析 |
| GET  | `/api/entities/{identifier}` | 同上（路径形式） |

示例：

```bash
curl -s -X POST 'http://localhost:8080/api/snapshots/v1/imports?source=refseq&format=JSON' \
  -H 'Content-Type: text/plain' --data-binary @snapshots/v1_refseq.json
curl -s -X POST 'http://localhost:8080/api/snapshots/v2/switch'
curl -s 'http://localhost:8080/api/entities?identifier=NM_000518'
```

---

## 5. 本地验证方法

仅需 JDK 与 Maven，全部离线可运行（依赖已在本地仓库）：

```bash
# 编译
mvn -o compile

# 运行全部测试（离线）
mvn -o test

# 启动服务（可选，随后用上面的 curl 验证）
mvn -o spring-boot:run
```

### 测试覆盖（`src/test/...`，资源在 `src/test/resources/snapshots/`）
- **SnapshotParserTest**（6 项）：三种格式的畸形记录、缺字段、非法标识、坏表头/坏引号、
  来源内重复，逐类断言原因可区分。
- **IdentifierResolverTest**（5 项）：跨源 xref 合并、来源优先级裁决、空值跳过、
  跨类型碰撞/悬挂引用留痕、打乱顺序结论一致（可复现）。
- **SnapshotServiceIntegrationTest**（3 项）：多源聚合、重复导入幂等、异内容重导入 409、
  版本切换与回退看到不同完整视图。
- **ConcurrentSnapshotConsistencyTest**（3 项）：
  切换/刷新与大量查询并发——每次查询都校验“同版本盖章 + 完整基数 + 无新旧值混合”；
  并发相同导入幂等（恰 1 个 CREATED）；并发异内容绝不覆盖已发布版本。
- **AggregatorWebTest**（2 项）：REST 导入/幂等/409、版本切换与历史结论复现。

> 测试日志会打印**输入记录、快照版本与判定依据**（SLF4J），例如解析器测试打印每条
> ACCEPTED/REJECTED/CONFLICT 及原因，解析器（resolver）打印每个实体的
> `snapshot / rulesVersion / entityKey / identifiers / conflicts / winner+priority`。
