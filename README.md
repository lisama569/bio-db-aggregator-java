# 本地生物数据库记录聚合与跨源标识符解析服务

一个纯本地、零外部依赖网络的 Spring Boot 服务：用**本地快照文件**模拟多个生物数据库
（RefSeq / UniProt / Ensembl 风格）的数据导出，完成多来源归一化导入、跨源标识符解析、
按**可声明规则集（rule sets）**进行的字段冲突确定性裁决、快照版本切换/回退，并保证并发
查询始终看到**单一 (版本, 规则集) 的完整视图**。规则集从本地 JSON 加载、可多套并存、
按版本盖章钉住，使历史结论在规则变更后仍可原样复现，并可对比同一数据在两套规则下的差异。

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

## 2. 跨源标识符解析与可声明的规则集

### 2.1 规则集的声明与加载（本地、不联网、不写死在代码里）

解析规则不再是代码里固定的一套，而是从**本地 JSON 声明文件**加载的**规则集
（RuleSet）**，每套有唯一可区分的 `id`：

- 默认扫描 classpath 下的 `rules/*.json`（`classpath*:rules/`，主程序与测试的根都会被
  扫描）；可通过 `aggregator.rules.locations` 追加本地目录，例如
  `aggregator.rules.locations[1]=/etc/bio-aggregator/rules`。全程无网络。
- 启动时若存在任何坏规则文件，应用**直接拒绝启动**；运行期 `POST /api/rules/reload`
  会逐条返回被拒绝文件及可区分原因，绝不会静默退回默认规则继续算。

规则集 JSON 结构：

```json
{
  "id": "priority-rules-ensembl-v1",
  "description": "Ensembl-first 示例",
  "sources": ["refseq", "uniprot", "ensembl", "genbank", "ncbi"],
  "sourcePriority": ["ensembl", "uniprot", "refseq", "genbank", "ncbi"],
  "strictSources": false,
  "fieldRules": {
    "name": { "priority": ["ensembl", "uniprot", "refseq", "genbank", "ncbi"] }
  }
}
```

- `sources`：该规则集认识的来源；
- `sourcePriority`：字段裁决的来源顺序（高 → 低），必须是 `sources` 的无重复排列；
- `fieldRules`：按字段（`symbol/name/sequence/organism`）覆盖全局优先级；
- `strictSources`：`true` 时，数据/ xref 里出现未声明来源会在解析时被**明确拒绝**；
  `false`（默认）时未声明来源按名称升序追加在已知来源之后（兼容历史行为）。
- 每份声明内容计算 SHA-256（`contentHash`），随规则集一起被快照钉住。

工程随附两套：`priority-rules-v1`（旧默认优先级，历史结论的盖章规则）与
`priority-rules-ensembl-v1`（Ensembl 优先，`name` 字段带字段级覆盖，用于对照）。

#### 坏规则被拒绝的原因（`RuleRejectionReason`，可区分）

| 原因 | 含义 |
|---|---|
| `MISSING_RULE_SET_ID` | 缺少/空白 `id` |
| `MISSING_SOURCE_PRIORITY` | 缺少空的来源优先级 |
| `DUPLICATE_SOURCE_IN_PRIORITY` | 优先级（含字段级）里同一来源重复出现 |
| `UNKNOWN_SOURCE_REFERENCED` | 优先级/字段规则/（strict 时）数据引用了未在 `sources` 声明的来源 |
| `UNKNOWN_FIELD_REFERENCED` | 字段规则引用了不参与裁决的字段 |
| `DUPLICATE_RULE_SET_ID` | 两个文件声明了同一个规则集 id |
| `MALFORMED_RULE_DOCUMENT` | 文件不可读或 JSON 语法损坏 |

启动期发现上述任一问题会拒绝启动；`POST /api/rules/reload` 返回 HTTP 200 但在
`failures` 中逐条列出（`origin/ruleSetId/reason/detail`）；在 **strict 规则集**下解析
数据时遇到未声明来源，`.../resolution[?rules=]` 返回 HTTP 422，错误体带
`reason=UNKNOWN_SOURCE_REFERENCED`；显式命名一个未加载的规则集返回 HTTP 400
（`UNKNOWN_RULE_SET`）。任何路径都不会静默退回默认规则继续算。

### 2.2 规则版本管理与历史结论复现

- **每个已发布快照版本在首次导入时“钉住”（pin）当时使用的规则集**：该不可变
  RuleSet 实例永久挂在版本上，结论里的每个实体都盖 `rulesVersion = 规则集 id`。
- 之后切换激活规则集、甚至修改规则文件并 `reload`，都**不会改写**老版本的盖章结论；
  回退/查看老版本拿到的永远是当时那套规则的结果。
- **复现时可明确指定规则**：`GET /api/snapshots/{version}/resolution?rules=<id>`
  会用指定规则集对同一份数据重新裁决并缓存，**不动**盖章结论；同一份数据因此可以
  关联多套规则、各自保留一份结论（同视图内按规则 id 缓存）。
- 导入时可显式命名规则：`POST .../imports?rules=<id>`。若该版本已盖章为另一套规则，
  导入被拒绝并返回 `409 RULE_SET_MISMATCH`（报告会带回实际钉住的 `pinnedRuleSetId`），
  不允许悄悄换规则重算历史。命名同一套规则不受影响（幂等）。
- 激活规则集：`POST /api/rules/{id}/activate`（影响之后的新发布与无 `rules` 参数的
  当前查询默认值）；未知 id 返回 404，不会退回默认。
- **兼容边界**：合并（并查集、xref 等价关系）本身不依赖优先级；规则集只改变字段裁决、
  实体锚点（entityKey）以及 strict 模式下可接受的来源集合。旧解析器的 CSV 配置
  （`aggregator.source-priority`）仅保留给直接 new 解析器的旧单测使用。

### 2.3 两套规则下的差异对比

`GET /api/snapshots/{version}/resolution/compare?rulesA=<id>&rulesB=<id>`
返回 `ResolutionComparison`：

- 实体合并是否一致（以成员 home token 排序签名识别同一簇，`clusterDifferences`）；
- 字段最终胜出者与胜出值的变化（`fieldDifferences`：field / 双方 winnerSource+value）；
- 冲突归因差异（`conflictsOnlyUnderA/B`）；
- 歧义差异（`ambiguitiesOnlyUnderA/B`：悬挂引用、跨类型碰撞是否变化）；
- `equivalent=true` 表示四方面均无差异。

### 2.4 合并依据（等价关系）
- 每条记录拥有其“本来源身份 token”：`source:localId`。
- 记录通过**显式 xref** 引用 `namespace:value`：
  - 若同类型记录拥有该 token（其 `source==namespace` 且 `localId==value`）或断言同一 token，
    二者合并；
  - 连接用并查集计算，满足传递性，且与记录到达顺序无关。
- 基因与蛋白**永不跨类型合并**。

### 2.5 不静默处理的歧义（`AmbiguousLink`）
- `DANGLING_REFERENCE`：xref 指向任何记录都不拥有的 token（悬挂引用），不断言合并；
- `CROSS_TYPE_COLLISION`：同一 token 被不同类型实体声明（如某蛋白引用了某基因的 id）。

两者都写入 `ResolutionReport.ambiguities`，给出 token、owner 与 claimer，绝不静默取其一。

### 2.6 字段裁决
对每个合并后实体，`symbol/name/sequence/organism` 按**当前规则集对该字段的来源顺序**，
取第一个提供非空值的来源（字段可用 `fieldRules` 单独覆盖）。随附默认规则集
`priority-rules-v1` 的顺序（高 → 低）：

```
refseq > uniprot > ensembl > genbank > ncbi
```

- 非 strict 规则集中，未声明来源按名称升序追加；strict 规则集则直接拒绝数据。
- 旧 CSV 配置 `aggregator.source-priority=srcA,srcB,...` 只影响直接实例化的解析器
  （遗留单测），不再影响服务端规则。

只要更低优先级来源给出了**不同的非空值**，就生成一条 `ResolutionConflict`，记录
`field / winnerSource / winnerValue / loserSources / loserValues`（loser 按该字段顺序排序），
冲突被显式保留而不是被吞掉。

- 实体键：`TYPE:全局顺序最高来源:localId`（同来源并列按 localId 升序）。
- 实体、成员、歧义、map 迭代全部全序化，**同一快照 + 同一规则集**永远产生字节级一致的结论。

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
- 所有变更在同一把锁内完成，并以**一次**发布一个完整不可变 `SnapshotView`
  （内含按规则 id 缓存的多份不可变 `ResolutionReport`）结束；规则目录与激活指针也是
  一个不可变快照、原子发布；
- 查询（`queryAll` / `resolveIdentifier` / 取 snapshot/resolution）先**捕获一个视图
  与一份不可变规则集**，随后整个查询只针对该 `(视图, 规则集)`；
- 因此导入、版本切换与规则激活并发进行时，进行中的查询要么看到完整旧 `(版本,规则)`
  结论、要么看到完整新 `(版本,规则)` 结论，**绝不会出现一半旧规则一半新规则的混合裁决**；
- 同一份数据 + 同一套规则，无论线程调度/执行顺序如何，结论完全一致（解析是纯函数 +
  全序化输出，并发测试以数千万次查询校验）；
- 并发首次导入用 `putIfAbsent` 原子认领 `(version, source)`，避免互相覆盖；
  并发相同导入恰有 1 个 `CREATED`，其余全部 `DUPLICATE`。

---

## 4. REST API

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/snapshots/{version}/imports?source=&format=JSON\|CSV\|FLAT[&rules=]` | 以 `text/plain` 导入一个来源；200 `CREATED`/`DUPLICATE`，409 `CONFLICTING_REIMPORT` 或 `RULE_SET_MISMATCH` |
| POST | `/api/snapshots/{version}/switch` | 原子切换 current；未知版本 404 |
| GET  | `/api/snapshots` | 版本列表（发布顺序）、current 与当前激活规则 |
| GET  | `/api/snapshots/{version}/snapshot` | 该版本归一化记录、拒绝、来源内冲突 |
| GET  | `/api/snapshots/{version}/resolution[?rules=]` | 无 `rules` 返回**盖章**结论；带 `rules` 用指定规则复现（不改动盖章结论）；未知规则 400 |
| GET  | `/api/snapshots/{version}/resolution/compare?rulesA=&rulesB=` | 同一数据在两套规则下的合并/字段胜者/冲突/歧义差异 |
| GET  | `/api/entities?identifier=[&rules=]` | 在 current 版本按 local id / `ns:id` / 实体键解析；省略 `rules` 用该版本盖章规则 |
| GET  | `/api/entities/{identifier}[?rules=]` | 同上（路径形式） |
| GET  | `/api/rules` | 列出已加载规则集及激活 id |
| POST | `/api/rules/{ruleSetId}/activate` | 原子切换激活规则；未知 id 404 |
| POST | `/api/rules/reload` | 重新扫描本地规则文件并原子发布目录；逐个返回坏文件及原因 |

**多规则并存时查询如何选规则**：每个版本首次发布时钉住一套规则；省略 `rules`
参数的查询（含 current 实体查询）始终返回该版本盖章规则下的结论，与“当前激活规则”
解耦；显式给 `rules=<id>` 则按指定规则重新裁决（结论被缓存保留）。切换激活规则只影响
之后的新发布以及作为新发布缺省值的选择，不影响任何已发布版本。

示例：

```bash
# 首发布时显式用 ensembl 规则盖章
curl -s -X POST 'http://localhost:8080/api/snapshots/v1/imports?source=refseq&format=JSON&rules=priority-rules-v1' \
  -H 'Content-Type: text/plain' --data-binary @snapshots/v1_refseq.json

# 老版本的盖章结论（不受后来规则切换影响）
curl -s 'http://localhost:8080/api/snapshots/v1/resolution'

# 明确用另一套规则复现同一份数据
curl -s 'http://localhost:8080/api/snapshots/v1/resolution?rules=priority-rules-ensembl-v1'

# 对比两套规则
curl -s 'http://localhost:8080/api/snapshots/v1/resolution/compare?rulesA=priority-rules-v1&rulesB=priority-rules-ensembl-v1'

# 切换激活规则 / 重新扫描本地规则文件
curl -s -X POST 'http://localhost:8080/api/rules/priority-rules-ensembl-v1/activate'
curl -s -X POST 'http://localhost:8080/api/rules/reload'
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

### 测试覆盖（`src/test/...`；数据在 `src/test/resources/snapshots/`，规则在
`src/main/resources/rules/`、`src/test/resources/rules/`，坏规则在
`src/test/resources/bad-rules/`）
- **SnapshotParserTest**（6 项）：三种格式的畸形记录、缺字段、非法标识、坏表头/坏引号、
  来源内重复，逐类断言原因可区分。
- **IdentifierResolverTest**（5 项）：跨源 xref 合并、来源优先级裁决、空值跳过、
  跨类型碰撞/悬挂引用留痕、打乱顺序结论一致（可复现）。
- **SnapshotServiceIntegrationTest**（3 项）：多源聚合、重复导入幂等、异内容重导入 409、
  版本切换与回退看到不同完整视图。
- **ConcurrentSnapshotConsistencyTest**（3 项）：
  切换/刷新与大量查询并发——每次查询都校验“同版本盖章 + 完整基数 + 无新旧值混合”；
  并发相同导入幂等（恰 1 个 CREATED）；并发异内容绝不覆盖已发布版本。
- **AggregatorWebTest**（3 项）：REST 导入/幂等/409、版本切换与历史结论复现、空列表。
- **RuleSetManagementTest**（6 项）：好规则从本地文件加载（含字段级覆盖、contentHash）；
  坏规则被拒绝且 7 类原因可区分（重复来源、未声明来源、空优先级、未知字段、坏 JSON、
  缺 id、跨文件重复 id）；直接 `validate()` 拒绝；注册表激活/未知 id 拒绝/ reload 后
  已钉住的不可变实例不变；坏目录启动时明确失败。
- **RuleSetReproducibilityIntegrationTest**（5 项）：同一数据在两套规则下并存且两份结论
  都保留；切换激活规则后老版本盖章结论原样可取、并可显式用新规则复现；给已盖章版本
  指定另一套规则导入被 `RULE_SET_MISMATCH` 拒绝；未知规则集不退回默认；strict 规则集
  对未声明来源给出可区分拒绝。
- **RuleSetWebTest**（1 项）：规则列表/激活/ reload、按 `rules` 取结论与对比、
  `RULE_SET_MISMATCH` 409、未知规则 400。
- **ConcurrentRuleConsistencyTest**（1 项）：查询（盖章 + 指定规则）、幂等导入、版本切换、
  规则激活同时并发，校验每次读取都是单一 `(版本,规则)` 的完整结论（约数千万次查询，
  无混合、无新旧 winner 串扰），同数据同规则结论恒定。

> 测试日志会打印**输入记录、数据版本、使用的规则集与判定依据**（SLF4J），例如解析器
> 测试打印每条 ACCEPTED/REJECTED/CONFLICT 及原因；resolver 对每个实体打印
> `snapshot / rules / entityKey / identifiers / conflicts / 每个字段 winner+生效优先级`，
> 歧义与整轮统计也带 `snapshot + rules`；规则加载打印每个文件的接受/拒绝原因，
> 对比接口打印两个规则 id 与四类差异计数。

### 手工验证规则复现（离线）

```bash
mvn -o spring-boot:run
# 另一个终端：
curl -s -X POST 'localhost:8080/api/snapshots/demo/imports?source=refseq&format=JSON&rules=priority-rules-v1' \
  -H 'Content-Type: text/plain' \
  --data '[{"id":"NM1","type":"gene","symbol":"HBB","name":"refseq name","xrefs":{"ensembl":"E1"}}]'
curl -s -X POST 'localhost:8080/api/snapshots/demo/imports?source=ensembl&format=FLAT&rules=priority-rules-v1' \
  -H 'Content-Type: text/plain' --data $'REC|E1|gene|HBB|ensembl name|||refseq:NM1\n'
curl -s 'localhost:8080/api/snapshots/demo/resolution?rules=priority-rules-ensembl-v1'
```
