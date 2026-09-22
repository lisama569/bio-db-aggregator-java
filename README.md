# bio-db-aggregator-java

本地生物数据库记录聚合与跨源标识符解析服务。用本地快照文件（JSONL / CSV / TSV）
模拟 NCBI、UniProt、Ensembl 等不同来源的数据导出，在进程内完成多来源归一合并、
跨源标识符解析、快照版本切换与并发一致查询。**不访问任何外部网络、真实数据库或凭据。**

- Java 21 + Spring Boot 3.4 + JUnit 5，仅使用工程已有依赖（Jackson 随 Spring Boot 提供）。
- 纯内存快照仓库，零外部中间件，`mvn test` 即可在本地完整验证。

---

## 1. 目录结构

```
src/main/java/com/github/lisama569/biodbaggregatorjava
├── model/      统一模型：BioRecord / ResolvedEntity / RejectedRecord /
│               AttributeDecision / RejectReason / ResolutionStatus ...
├── snapshot/   快照解析与归一：JSONL、CSV/TSV 解析器 + RecordNormalizer + 不可变 Snapshot
├── resolve/    ResolutionRules + IdentifierResolver（并查集聚类 + 确定性裁决）
├── store/      SnapshotStore（原子切换、内容寻址幂等、历史版本）
├── service/    SnapshotImporter（导入流水线）、AggregationService（版本一致查询）
└── web/        AggregationController（REST 接口）
src/test/resources/snapshots/  本地快照夹具（含畸形/冲突行）
```

## 2. 支持的来源格式

| 格式 | 枚举 | 说明 |
|---|---|---|
| JSON Lines | `JSONL` | 每行一个 JSON 对象，空行跳过 |
| CSV | `CSV` | 逗号分隔、首行表头，支持 `"` 引号包裹与 `""` 转义、引号内换行 |
| TSV | `TSV` | 制表符分隔，规则同 CSV |

每行支持的字段（大小写不敏感的表头 / JSON 键）：

| 字段 | 含义 | 必填 |
|---|---|---|
| `id` | 记录在来源内的主键 | ✔ |
| `type` | `gene` / `protein`（其他值拒绝） | ✔ |
| `geneId` / `proteinId` / `refseq` / `uniprot` / `ensembl` / `ncbi` | 跨源标识符列 | 至少一个跨源标识符（或 `symbol`） |
| `identifiers`（仅 JSONL） | 自由标识符对象 `{"ns":"value"}` | 可选 |
| `symbol` | 基因符号 / 蛋白简称（弱标识符，按实体类型隔离聚类） | 可选 |
| `name` | 全称 / 描述 | 可选 |
| `sequence` | 序列 | 可选 |

空字符串按缺失处理；缺失字段不会被当作合法值。

### 坏记录归类（原因可区分，绝不静默丢弃）

| RejectReason | 触发条件 |
|---|---|
| `MALFORMED_RECORD` | JSON 截断/非法、每行不是 JSON 对象、标量字段给成对象/数组、CSV 列数与表头不一致、引号未闭合、引号非法出现 |
| `MISSING_REQUIRED_FIELD` | 缺 `id`、缺 `type`、`type` 非 gene/protein、没有任何跨源标识符 |
| `WITHIN_SOURCE_ID_CONFLICT` | 同一来源内同一主键出现内容不一致的多条记录：每条都追加可区分的拒绝记录，实体同时标记为 **AMBIGUOUS**（见下） |
| `UNSUPPORTED_FORMAT` | 未知来源格式 |

被拒绝的记录保留来源、行号、原始文本、原因与说明，可通过 `ImportReport.rejected()`
和 `GET /api/rejected` 查询；任何单行错误都不阻断同文件后续行的解析。

## 3. 跨源标识符解析与优先级

解析器 `IdentifierResolver` 的流程是完全确定性的（相同输入 + 相同规则 ⇒ 相同结论）：

1. **聚类**：把每条记录的所有标识符（`namespace:value`）作为节点，
   同记录内的标识符用并查集（DSU）合并；不同记录共享同一标识符即连通成同一实体簇。
   `symbol` 会在基因与其编码蛋白之间天然共享，因此 **symbol 节点按实体类型隔离**，
   避免基因与蛋白被错误合并；其余命名空间全局连通。
2. **来源内冲突**：同 `source + id` 的多条记录——
   完全相同则去重（不产生重复计数）；内容不一致则实体标记为 `AMBIGUOUS`，
   不静默选一值，给出冲突说明与全部成员。
3. **属性裁决**（`symbol` / `name` / `sequence` 各自独立）：
   - 先比较**来源优先级**：`ensembl-snapshots > uniprot-snapshots > ncbi-snapshots`
     （规则版本 `rules-v1`，见 `ResolutionRulesConfig`）；
   - 名次严格优先者胜出，`basis` 记录“按来源优先级裁决，来源 X(rank=n) 胜出”；
   - 来源名次相同（含来源不在优先级表中）时**不静默取其一**：
     按确定性平局规则（来源内行号 `ordinal`，再按值字典序）给出结论，
     属性标记 `tieBreak=true`，实体状态为 `CONFLICT`，并在结论中保留全部候选值与出处；
   - 所有候选值及来源始终保留在 `AttributeDecision.candidates` 中。
4. **规范标识符 canonicalId**：按**命名空间优先级**
   `uniprot > refseq > ensembl > geneId > proteinId` 取簇内最高优先级标识符；
   同命名空间出现多个不同值属于身份歧义，实体降级为 `AMBIGUOUS`。
5. **结论状态**：`RESOLVED`（一致或已严格裁决）、`CONFLICT`（平局裁决并保留候选）、
   `AMBIGUOUS`（身份歧义：来源内冲突 / 同命名空间多值 / 类型冲突，不得静默取其一）。

### 可复现性

- 每个快照都记录内容哈希（SHA-256，覆盖规则版本 + 全部输入的来源名/格式/内容），
  并保存生成时的 `rulesVersion`。
- 规则（`ResolutionRules`）升级只影响之后导入的快照；历史快照的结论不变、
  可随时读取或回退复现。

## 4. 快照版本切换与并发一致性语义

`SnapshotStore` 是纯内存的版本仓库：

- **不可变快照**：导入时一次性完成 解析 → 归一 → 跨源解析，产物 `Snapshot`
  （记录、拒绝记录、实体索引、标识符反查索引）全部不可变。
- **原子切换**：当前版本由一个原子引用持有，安装/回退都是整体替换。
  查询方法在入口只读取一次当前快照引用，随后全程基于该快照计算——
  进行中的查询看到的要么是旧版本完整视图、要么是新版本完整视图，
  **不会出现新旧混合**（`SnapshotConsistencyTest.concurrentQueriesSeeOneConsistentVersionDuringRefresh`
  在 8 个查询线程与 1 个刷新线程并发下校验“版本号 ↔ 该版本数据”严格自洽）。
- **历史版本**：所有已导入版本永久保留、不可覆盖；
  `rollback(version)` 只是把当前引用指回历史快照，不生成新版本，历史结论可复现。
- **幂等导入**：以内容哈希去重，相同输入（规则也相同）重复导入直接复用既有版本并把当前视图
  原子切回该版本（与显式回退等价），返回 `idempotentReuse=true`；
  不产生新版本、不产生重复记录或计数漂移。并发交替导入同一批快照最终版本数仍为 2（v1/v2）。

## 5. REST 接口

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/snapshots/import` | 导入一组来源快照（请求体 `{"sources":[{"source","format","content"}]}`） |
| GET  | `/api/snapshots/current` | 当前版本号、规则版本、实体数 |
| GET  | `/api/snapshots/versions` | 全部版本元信息（含 contentHash、记录数、是否当前） |
| POST | `/api/snapshots/rollback/{version}` | 原子回退到历史版本 |
| GET  | `/api/entities` | 当前版本全部解析结论（含状态与判定依据） |
| GET  | `/api/entities/{namespace}/{value}` | 按任意标识符查询，如 `/api/entities/uniprot/P04637` |
| GET  | `/api/rejected` | 当前版本被拒绝/冲突记录及原因 |

## 6. 本地验证方法

```bash
# 离线全量测试（13 个测试：畸形归类 / 跨源冲突 / 来源内冲突 / 幂等 / 回退 / 并发）
mvn -o test
# 联网环境同样可以直接 mvn test

# 启动服务后可用 curl 验证
mvn spring-boot:run
```

```bash
curl -s -X POST localhost:8080/api/snapshots/import \
  -H 'Content-Type: application/json' \
  -d '{"sources":[{"source":"uniprot-snapshots","format":"TSV",
       "content":"id\ttype\tuniprot\tsymbol\tname\nUP1\tprotein\tP04637\tTP53\tp53\n"}]}'
curl -s localhost:8080/api/entities/uniprot/P04637
curl -s localhost:8080/api/rejected
curl -s -X POST localhost:8080/api/snapshots/rollback/snapshot-1-xxxxxxxxxxxx
```

### 测试覆盖与日志

- `MalformedRecordTest`：JSONL 截断、未知类型、缺标识符；CSV/TSV 缺列、缺主键、
  列数不符；引号转义与行内换行。
- 来源内主键冲突在导入报告中以 `WITHIN_SOURCE_ID_CONFLICT` 独立原因呈现，
  对应实体结论为 `AMBIGUOUS`（见 `withinSourceConflictIsRejectedWithDistinctReasonAtImport`）。
- `CrossSourceResolutionTest`：来源/命名空间优先级裁决、表外来源不能盖过表内来源、
  表外来源间平局确定性冲突且可复现、来源内主键冲突 ⇒ AMBIGUOUS、完全重复去重。
- `SnapshotConsistencyTest`：重复导入幂等无计数漂移、v1↔v2 回退历史结论复现、
  并发刷新/查询下版本视图自洽。
- `WebApiIntegrationTest`：Spring 容器装配 + HTTP 导入/查询/回退/幂等。

测试日志中会打印**输入记录、快照版本、规则版本与判定依据**（查找
`[test:malformed-*]`、`[test:resolution]`、`[test:tie-conflict]`、
`[snapshot-import]`、`[snapshot-rollback]` 等前缀）。
