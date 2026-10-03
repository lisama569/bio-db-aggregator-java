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

解析由 `PriorityIdentifierResolver` 实现，结论带 **rulesVersion**（即产生该结论的规则集
id）。来源优先级与字段裁决**不再写死在代码里**，而是由可声明、可加载、内容寻址的**规则集
（RuleSet）**提供；可以并存多套规则，历史结论始终钉在发布时使用的那套规则上（见 2.4–2.9）。
内置默认规则集 `priority-rules-v1` 的优先级与字段策略与历史行为完全一致。

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


### 2.4 规则集怎么声明（本地 JSON，不写死、不联网）

规则集是一份本地 JSON 文件（启动时扫描，不访问网络），内置两份放在
`src/main/resources/rulesets/`：

- `priority-rules-v1.json`：默认集，全局优先级
  `["refseq","uniprot","ensembl","genbank","ncbi"]`，各字段均为 `SOURCE_PRIORITY`，
  与历史内置结论**字节级一致**；
- `ensembl-first-v1.json`：对照集，全局优先级 `["ensembl","uniprot","refseq",...]`，
  且 `name` 字段改用 `PREFER_LONGEST` 策略。

声明格式：

```json
{
  "id": "my-rules-v1",
  "description": "optional human note",
  "sourcePriority": ["uniprot", "refseq", "ensembl"],
  "fields": {
    "name": {"strategy": "PREFER_LONGEST", "priority": ["refseq", "uniprot"]},
    "sequence": {"strategy": "SOURCE_PRIORITY"}
  }
}
```

| 键 | 含义 |
|---|---|
| `id` | 规则集唯一标识，同时作为结论里盖章的 `rulesVersion`；**改规则请发新 id** |
| `sourcePriority` | 全局来源顺序（高到低），必填非空；列表外来源按名称升序追加在后 |
| `fields.<field>.strategy` | `SOURCE_PRIORITY`（默认，按优先级取首个非空）或 `PREFER_LONGEST`（最长非空值胜，长度并列按字段来源顺序破平） |
| `fields.<field>.priority` | 可选的字段级来源顺序覆盖；列出的来源排在前面，其余来源回退到全局顺序 |

可覆盖的字段固定为 `symbol / name / sequence / organism`。

### 2.5 规则集怎么加载

- 默认扫描 classpath 目录 `classpath*:rulesets/*.json`（目录可用
  `aggregator.ruleset.classpath-dir` 配置）；
- 另可用逗号分隔配置一个或多个**本地文件系统目录**：
  `aggregator.ruleset.dirs=/etc/bio/rulesets,./local-rules`（不存在的目录只告警、跳过）；
- 默认使用的规则集 id 由 `aggregator.ruleset.default-id` 指定（默认
  `priority-rules-v1`）；加载不到默认集时**启动直接失败**，不会静默退回硬编码规则。

注册是**内容寻址、只增不改**的：同一 id + 同一内容（按原始 JSON 字节算 SHA-256）重复注册
是幂等；同一 id 但内容不同会被拒绝（`DUPLICATE_RULESET_ID`）。规则永远不会被原地替换，
因此盖章过的 id 可以永久复现当时结论。

### 2.6 坏规则必须被明确拒绝（原因可区分）

加载时逐文件校验，坏规则抛 `RuleSetException`，携带机器可区分的 `reason`
（REST 下 400/404 响应体含 `reason` 字段），**绝不静默忽略或退回默认规则继续算**：

| `RuleSetFailureReason` | 触发条件 |
|---|---|
| `INVALID_SYNTAX` | 不是合法 JSON、顶层不是对象、数组形状不对等 |
| `BLANK_ID` | `id` 缺失或空白 |
| `EMPTY_PRIORITY` | `sourcePriority` 缺失或为空 |
| `DUPLICATE_PRIORITY_SOURCE` | 全局或字段级优先级里同一来源重复出现 |
| `UNKNOWN_STRATEGY` | 字段策略不是 `SOURCE_PRIORITY` / `PREFER_LONGEST` |
| `UNKNOWN_FIELD` | 字段覆盖引用了不在规范模型里的字段 |
| `OVERRIDE_SOURCE_NOT_DECLARED` | 字段级 `priority` 引用了全局优先级里没声明的来源 |
| `DUPLICATE_RULESET_ID` | 同 id 不同内容再次注册 |
| `DEFAULT_RULESET_NOT_FOUND` | 配置的默认规则集 id 没被加载到（启动失败） |
| `UNKNOWN_RULESET` | 查询 / 切换 / 钉版本时引用了未注册的规则集 id（404） |

### 2.7 规则版本怎么管理：钉版本（pin）与不可变复现

- 每个数据版本在**首次创建时**被钉（pin）到当时选中的规则集 id；该绑定之后**永不改变**。
- 钉住的历史结论在后续导入重发布时，始终用**钉住的规则集 + 当前数据**重算并覆盖发布，
  数据变化不会错误复用旧结论；规则集本身不可变，所以历史规则语义恒定。
- 之后无论怎样切换当前规则、换一套全新优先级，回退或重查老版本拿到的
  **仍然是当时那套规则下的结论**，不会被新规则悄悄改写。
- 同一份数据快照可以**关联多套规则集，各生成一份结论并同时保留**：显式用某规则 id
  复算的结论按 `(version, rulesId)` 不可变缓存（数据未变时命中缓存；该版本新增来源导致
  数据变化时，这类附加缓存会被清空重算，绝不会返回“旧数据 + 某规则”的陈旧结果）。
- 要明确指定规则复现时：服务层 `getResolution(version, rulesId)`、
  `resolveIdentifier(version, rulesId, id)`；REST 用 `?rules=` 参数。
- **新建版本**用“当前选中”的规则钉版本；**老版本**永远用自己的 pin。两者互不干扰。

### 2.8 多规则并存时查询怎么选规则

- 不带规则参数（`GET /api/entities`、`/api/snapshots/{v}/resolution`）：
  读该数据版本**钉住的历史结论**（与当前选中哪套规则无关）。
- 带 `?rules=<id>`：读该版本在**指定规则集**下的结论（不存在则 404
  `UNKNOWN_RULESET`，不会偷偷用默认规则顶替）。
- `GET /api/snapshots/{version}/pinned-rules`：查看该版本历史结论钉在哪套规则。
- `POST /api/rules/{rulesId}/switch`：原子切换“当前规则集”，只影响之后的新读取默认选择
  与**新建版本**的 pin，不影响任何已发布版本的历史结论。

### 2.9 两套规则下的结论对比（`ResolutionComparison`）

`GET /api/snapshots/{version}/rule-comparison?rulesA=&rulesB=`（或
`compareRules(version, a, b)`）对**同一份数据**在两套规则下的结论做确定性对比：

- 实体按**稳定簇身份**（类型 + 排序后的成员 `source:localId` 集合）配对，而不是按
  本身依赖优先级的实体键，因此“锚点来源翻转”也能对齐成同一簇；
- 逐字段（`symbol/name/sequence/organism`）报告赢家来源与取值差异
  （`FieldWinnerDifference`，含双方 winnerSource / winnerValue）；
- 报告实体键变化、成员合并是否变化（`mergeChanged`）、字段冲突数增减、
  仅在某一侧出现的歧义 token，以及 `identical` 总判定。

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
  回退后结论与当时完全一致（数据不可变 + 版本钉住的规则集不可变，见 2.7）。
- 切换到未知版本返回 404。
- 规则切换（`POST /api/rules/{rulesId}/switch`）是另一个维度的原子发布，只移动“当前规则
  指针”，不会改动任何版本已钉住的结论。

### 3.3 并发一致性（copy-on-publish + 不可变视图）
- 所有变更（导入重发布、切版本、按需复算某规则结论）在同一把锁内完成，并以**一次**
  发布一个完整不可变 `SnapshotView`（内含 `rulesId -> ResolutionReport` 的不可变映射）结束；
- 规则集“当前 id”放在 `AtomicReference`，读取先捕获一次 id/规则对象，
  随后整个查询只用这套规则；
- 因此“有人在查、有人在导入或切版本、有人在切规则”并发进行时，进行中的查询要么看到
  完整旧 (版本,规则) 结论、要么看到完整新 (版本,规则) 结论，
  **绝不会出现一半旧规则、一半新规则的混合结论，也不会缺半截记录**；
- 同一份数据配同一套规则，结论是纯函数：与执行顺序、线程交错无关，重复读取严格相等；
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
| GET  | `/api/snapshots/{version}/resolution[?rules=<id>]` | 跨源解析结论；不带 `rules` 返回版本钉住的历史结论，带 `rules` 返回指定规则下结论（未知 404 `UNKNOWN_RULESET`） |
| GET  | `/api/snapshots/{version}/pinned-rules` | 该版本历史结论钉住的规则集 id |
| GET  | `/api/snapshots/{version}/rule-comparison?rulesA=&rulesB=` | 同一份数据在两套规则下的差异对比 |
| GET  | `/api/rules` | 已注册规则集（id、优先级、字段策略、内容 hash）与当前选中 id |
| POST | `/api/rules/{rulesId}/switch` | 原子切换当前规则集；未知 id 404 |
| GET  | `/api/entities?identifier=&rules=` | current 版本查询；`rules` 指定读哪套规则的结论，缺省读钉住结论 |
| GET  | `/api/entities/{identifier}` | 按 local id / `ns:id` / 实体键解析（钉住结论） |

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

### 测试覆盖（`src/test/...`，资源在 `src/test/resources/snapshots/` 与
`src/test/resources/{rulesets,bad-rulesets}/`）
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
- **RuleSetLoaderTest**（3 项）：7 类坏规则各带可区分 `RuleSetFailureReason` 被拒绝；
  合法规则解析优先级/字段策略/内容 hash；同 id 异内容注册被拒、未知规则 404。
- **RuleSetManagementIntegrationTest**（5 项）：切规则后老结论原样取回且显式可复现、
  同一数据两套规则并存、差异对比（实体键/字段赢家/冲突数）、未知规则拒绝不默认顶替、
  切规则后新建版本钉到新规则。
- **ConcurrentRuleConsistencyTest**（1 项）：查询 + 幂等导入 + 切版本 + 切规则并发约 3s，
  对四个 (版本,规则) 组合反复校验“同一份盖章 + 重复读取严格相等 + 赢家必为贡献来源”。
- **RuleSetWebTest**（1 项）：规则列表/切换/按规则查询/对比/钉版本/未知规则 404 reason。

### 规则集相关的本地验证（curl）

```bash
# 查看已注册规则集与当前规则
curl -s http://localhost:8080/api/rules

# 老版本钉住的历史结论（不受当前规则影响）
curl -s http://localhost:8080/api/snapshots/v1/pinned-rules
curl -s http://localhost:8080/api/snapshots/v1/resolution

# 显式用另一套规则复现同一份数据（并存、不覆盖历史结论）
curl -s 'http://localhost:8080/api/snapshots/v1/resolution?rules=ensembl-first-v1'

# 对比两套规则的判定差异（合并/字段赢家/冲突/歧义）
curl -s 'http://localhost:8080/api/snapshots/v1/rule-comparison?rulesA=priority-rules-v1&rulesB=ensembl-first-v1'

# 切换当前规则（只影响之后新读取默认选择与新建版本的 pin）
curl -s -X POST http://localhost:8080/api/rules/ensembl-first-v1/switch
```

> 复现范围与兼容边界：历史复现保证覆盖**同一份已聚合数据快照**在其钉住规则（或显式指定的
> 已注册规则）下的全部解析结论——合并结果、实体键、四个规范字段赢家与取值、字段冲突、
> 歧义列表，重复计算严格相等。规则集只增不改，改规则请发新 id；删除/不加载某规则集后，
> 已缓存的该规则结论仍可读，但无法再对新版本复算，直到重新提供同 id 同内容（同 hash）的
> 规则文件。内置 `priority-rules-v1` 与历史内置优先级/策略一致，旧用例结论不回归。

> 测试日志会打印**输入记录、数据版本、规则集 id/hash 与判定依据**（SLF4J），例如加载器打印
> 每个规则集的 `id/sourcePriority/fieldOverrides/hash`，解析器打印每个实体的
> `snapshot / rules / rulesHash / entityKey / identifiers / conflicts / winner+priority`，
> 对比服务打印每个簇的赢家迁移（`field:[winnerA->winnerB]`）。
