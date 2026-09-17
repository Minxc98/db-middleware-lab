# db-middleware-lab

用来验证各类数据库/存储中间件真实行为的实验场。每个中间件一个 Maven 模块,
测试环境由 Testcontainers 自动拉起,不依赖任何外部已部署的环境。

当前已落地:**Elasticsearch**。

## 环境要求

| 项 | 版本 |
| --- | --- |
| JDK | 21 |
| Maven | 3.9+(IDEA 内置即可,当前机器未把 `mvn` 加到 PATH) |
| Docker | 跑集成测试必需(Testcontainers 用) |
| Spring Boot | 3.5.6 |

## 模块结构

```
db-middleware-lab
├── .dockerignore                  在根:它约束 build context,而 context 就是仓库根
├── middleware-lab-common          跨中间件复用的探针 SPI、耗时统计、容器约定
│   ├── probe/MiddlewareProbe      每个中间件实现一次:"活着吗 / 什么版本"
│   ├── probe/ProbeResult
│   ├── metrics/Timings            统一的 wall-clock 计时
│   └── testsupport/MiddlewareContainers  容器复用开关
│
└── middleware-lab-elasticsearch   ES 行为验证(容器环境自带,见其 DOCKER.md)
    ├── Dockerfile                 多阶段构建(context 是仓库根)
    ├── docker-compose.yml         ES + lab 应用(+ Kibana / toolbox 两个可选 profile)
    ├── scripts/seed.sh            灌测试数据
    ├── scripts/es-inspect.sh      一把梭查看集群、磁盘、索引文件、容器资源
    ├── DOCKER.md                  容器化运行 & 内部观察手册
    ├── src/main/ ElasticsearchLabApplication  手工探索用的 Boot 应用(测试不依赖它)
    │         config/EsLabProperties       lab.es.* 配置,连接参数仍归 spring.elasticsearch.*
    │         domain/ProductDoc            示例文档:text / keyword / double / date 四类字段
    │         repository/ProductDocRepository   派生查询,用于对比 NativeQuery
    │         service/EsProbeService       MiddlewareProbe 的 ES 实现
    │         service/ProductIndexService  索引生命周期 + 写入 + refresh
    │         service/ProductSearchService 查询语义
    │         api/EsLabController          /api/es/** 手工触发
    └── src/test/ support/ElasticsearchContainerFactory  决定连哪个 ES(见下)
               support/AbstractElasticsearchIT       所有 IT 的基类,每个用例前重建索引
               support/ProductDocs                   测试数据
               ops/EsProbeIT            连通性冒烟
               index/IndexLifecycleIT   索引生命周期、近实时可见性
               search/ProductSearchIT   analyzed vs keyword、聚合、深分页
```

## 跑起来

集成测试类以 `IT` 结尾,由 failsafe 在 `verify` 阶段执行;`mvn test` 只跑单元测试。

```bash
mvn -DskipTests package && mvn verify
```

单独跑一个:

```bash
mvn -pl middleware-lab-elasticsearch verify -Dit.test=ProductSearchIT
```

容器默认每次构建重新拉起。想在多次构建之间复用,加 `-Dlab.containers.reuse=true`
并在 `~/.testcontainers.properties` 里设置 `testcontainers.reuse.enable=true`。

### 测试连的是哪个 Elasticsearch

按顺序决定,不需要任何配置就能跑:

1. 显式指定了 `-Dlab.es.external.uri=http://...`(或环境变量 `LAB_ES_URIS`)→ 用它;
2. `localhost:9200` 上已经有 ES 在应答 → 复用它(`docker compose up -d elasticsearch`
   留下的就是这个)。想关掉这层探测:`-Dlab.es.autodetect=false`;
3. 都没有 → Testcontainers 自己拉一个容器。

日志里会明确打出走的是哪条路。IDE 里右键直接跑测试走的就是第 2 条,不用配 run configuration。

测试用的索引是 `lab-product-it`(见 `application-test.yml`),和常驻的 `lab-product`
分开,所以复用长期运行的节点不会污染你手工灌的数据。

**为什么需要第 2 条**:第 3 条要求**跑测试的那个 JVM** 能连上 Docker daemon。如果 Docker
装在 WSL2 里而 IDEA 跑在 Windows 上,这条路不通 —— WSL 里的 `/var/run/docker.sock` 是
Linux 内核对象,Windows 进程访问不到,报错就是:

```
Could not find a valid Docker environment
```

端口转发帮不上忙:`localhost:9200` 能在 Windows 打开,转发的是**容器的服务端口**,
而**控制 Docker 的管理接口**没有转发。而且 Testcontainers 是要自己**新建**一个容器,
本来也不会复用已经跑着的那个。

CI 上没有常驻节点,自动落到第 3 条,行为和原来一样。

不想用 Testcontainers、想对着一个常驻节点手工试:

```bash
cd middleware-lab-elasticsearch && docker compose up -d elasticsearch
mvn -pl middleware-lab-elasticsearch spring-boot:run
```

然后 `GET http://localhost:8080/api/es/ping`。

## 骨架里留的扩展点

以下方法/用例是刻意留空的,按需要逐个填:

- `ProductIndexService#bulkSeed` — 批量灌数据并返回每批耗时,用于对比索引吞吐
- `ProductSearchService#countByBrand` — terms 聚合,验证分片数对聚合精度的影响
- `ProductSearchService#pageAll` — `search_after` 深分页,对比 from/size 超过 `max_result_window` 的行为
- `IndexLifecycleIT#mappingMatchesAnnotations` — 断言注解生成的 mapping
- `IndexLifecycleIT#aliasRollover` — 别名 + rollover 期间的读写切换
- `ProductSearchIT#aggregatesByBrand` / `#deepPaging` — 对应上面两个 service 方法

排查查询行为不符合预期时,把 `logging.level.org.springframework.data.elasticsearch.client.WIRE`
调成 `TRACE`,可以看到实际发出的 HTTP 报文。

## 新增一个中间件模块

1. 在根 `pom.xml` 的 `<modules>` 里加一行,并加一个 `xxx.image` 属性指向容器镜像;
2. 复制 `middleware-lab-elasticsearch` 的模块结构(含它自己的 Dockerfile / compose /
   scripts,compose 里换一个 project name),依赖 `middleware-lab-common`;
3. 实现 `MiddlewareProbe`,让新中间件接入统一的健康/版本上报;
4. 测试基类沿用"单例容器 + 每个用例前重置数据"的模式。

镜像版本统一在根 `pom.xml` 的属性里维护,通过资源过滤注入到
`src/test/resources/lab-containers.properties`(spring-boot-starter-parent 用 `@...@`
作为过滤分隔符)。IDE 里直接跑测试时若过滤未生效,代码会回退到
`ElasticsearchContainerFactory.DEFAULT_IMAGE`。

## 容器化运行 & 看内部细节

集成测试用 Testcontainers 自动拉容器,不需要手动起环境。如果想让环境常驻、
自己进去看 ES 的数据文件、段文件、磁盘水位、线程池排队情况:

```bash
cd middleware-lab-elasticsearch
docker compose up -d --build
./scripts/seed.sh
./scripts/es-inspect.sh
```

集群相关的行为(选主、副本放置、冷热分层)需要 3 节点环境,另起一套:

```bash
docker compose -f docker-compose.cluster.yml up -d    # es01/es02/es03 → 9201-9203
./scripts/cluster-chaos.sh status
```

集群测试用 `@EnabledIf` 探测:**集群没起就自动跳过**,不会让 `mvn verify` 失败。

容器环境跟模块走:每个中间件模块自带 Dockerfile、compose 和脚本,compose 的 project name
各不相同,几个 lab 可以同时开着。完整说明(数据目录布局、段文件后缀含义、水位线触发只读的
复现、常用 docker 排查命令)见
[middleware-lab-elasticsearch/DOCKER.md](middleware-lab-elasticsearch/DOCKER.md)。

## 测试覆盖地图

测试按 Dropbox 里那套 elasticsearch 笔记的章节组织,每个用例钉住一条具体行为。
92 个跑通 + 5 个显式跳过(需要停容器或第二个集群)。

| 笔记章节 | 测试类 | 钉住的行为 |
| --- | --- | --- |
| 02 §2 / 07 §4 / Q1.5 | `behavior/mapping/TextVsKeywordIT` | analyzer 三段流水线;term 查 text 查不到;keyword 大小写敏感;multi-field;text 聚合报 fielddata disabled |
| 07 §4 / 00 §9 | `behavior/mapping/DynamicMappingIT` | dynamic true 自动建字段;false 静默不可搜;strict 直接拒写;total_fields.limit 兜底 |
| 03 §2-3 / Q2.1 | `behavior/write/NearRealTimeIT` | 写完搜不到、refresh 后能搜;GET by id 实时;IMMEDIATE / WAIT_UNTIL;refresh_interval 动态改 |
| 02 §6 / 03 §4 | `behavior/write/TranslogAndFlushIT` | translog 累积与 flush 清空;refresh 不等于落盘;durability request/async |
| 02 §3 / 03 §5 / Q2.3 | `behavior/write/SegmentAndDeleteIT` | 更新=删+增;删除是软删除;**force merge 不立刻回收空间**;段数随 refresh 增长 |
| 03 §6.2 / Q2.2 | `behavior/write/OptimisticLockingIT` | _seq_no/_primary_term 暴露与递增;过期版本 409;retry_on_conflict |
| 03 §7 | `behavior/write/BulkWriteIT` | 批量写;**单条失败不回滚整批**;自动生成 _id |
| 04 §1-2 / Q3.3 | `behavior/query/QueryVsFilterIT` | filter 不算分(score=0);bool 四子句语义;minimum_should_match;match_phrase 词序 |
| 04 §3 | `behavior/query/RelevanceScoringIT` | TF 饱和;IDF 稀有词更重;字段长度归一化;explain 里的 k1/b 参数 |
| 04 §5 / Q3.2 | `behavior/query/PaginationIT` | from+size 超 max_result_window 报错;search_after 游标;PIT 冻结视图;流式导出 |
| 04 §6 / Q3.4 | `behavior/query/AggregationIT` | terms 桶与嵌套 metric;**doc_count_error_upper_bound 与 shard_size**;cardinality 近似;date_histogram |
| 05 §1,§4 / Q4.1 | `behavior/routing/RoutingIT` | 路由确定性;带 routing 只命中一个分片;**写时带 routing、读时不带就找不到**;倾斜 vs 均匀 |
| 05 §2 / Q4.1 | `behavior/routing/IndexSettingsIT` | 主分片数改不了(**关掉索引也改不了**);副本数可动态改;split / shrink;别名切换 |
| 05 §6 / 06 | `behavior/cluster/ClusterHealthIT` | 单节点 green/yellow;yellow 仍可读写;未分配的是副本;磁盘水位 |
| 06 §7 | `behavior/cluster/SnapshotRestoreIT` | 快照→删索引→恢复;第二次快照是增量 |
| 07 §5 / 05 §3 | `behavior/tuning/RolloverIT` | 写别名切换;条件不满足不滚动;**新索引可以换分片数** |
| 07 §2 | `behavior/tuning/BulkImportTuningIT` | 导入前 replica=0 + refresh=-1,导入后恢复 + force merge |
| 07 §4 / Q6.1 | `behavior/modeling/NestedObjectIT` | **object 数组扁平化导致跨元素误命中**;nested 修正;nested 的代价是每个元素一个 Lucene 文档 |
| 06 §1-2 / Q5.1 | `behavior/cluster/MasterElectionClusterIT` | **需要 3 节点**:唯一 master;voting configuration 自维护;**排除节点后投票配置保持奇数(3→1 而不是 2)** |
| 05 §5 / 06 §3 / 07 §5 | `behavior/cluster/ShardAllocationClusterIT` | **需要 3 节点**:副本不与主分片同节点;副本数>节点数的后果;冷热分层 allocation filtering;排除节点触发迁移;**filter 不会凌驾于可用性** |
| 06 §1-6 | `behavior/cluster/MultiNodeScenariosIT` | 仍然做不到的:副本升主、peer recovery、脑裂(要停容器/断网)、CCR/CCS(要第二个集群) — 保留 @Disabled,配 `scripts/cluster-chaos.sh` 手动验证 |

测试全部走 Spring Data 的 `ElasticsearchOperations` / `NativeQuery`;analyze、force merge、
flush、stats、snapshot 这些 Spring Data 没包的管理接口,用 Boot 自动装配的
`ElasticsearchClient`(Spring Data 自己也跑在它上面)。

### 跑测试时发现的、和笔记出入的地方

- **force merge 不一定回收空间**。笔记说"合并时真正物理删除被标记 deleted 的文档"。实测:
  即使 `only_expunge_deletes` 或 `max_num_segments=1`,`docs.deleted` 也不下降 —— 7.x 起的
  soft deletes 由 retention lease 保护(默认 12h,5 分钟同步一次),把
  `index.soft_deletes.retention_lease.period` 设成 `0s` 在测试的时间尺度内也不生效。
  这正是"删了一堆数据、force merge 过了、磁盘没降"的真实原因。
- **删除比更新多留一份**。更新一次 `docs.deleted` +1,删除一次 +2(多一个 tombstone)。
- **`_id` 不能用作 sort 字段**(ES 8 禁止对 `_id` 用 fielddata),`search_after` 的 tiebreaker
  要换成别的唯一 keyword 字段。
- **Java client 8.18.6 解不开 `_cluster/allocation/explain` 的响应**(Failed to decode response),
  排查分配问题得用 curl 或 Kibana,不能用 typed client。
- **Spring Data 把版本冲突翻译成 `OptimisticLockingFailureException`**,不是 ES 的
  `version_conflict_engine_exception`,异常链里也拿不到 ES 原始错误,断言要按 Spring 的来。
- **allocation filter 不会凌驾于"主副本不同节点"**。把一个 2 分片 1 副本的索引
  `require._name` 到单个节点,ES 会把分片**留在不合规的节点上**而不是变成 unassigned:
  `can_remain_on_current_node: no` + "isn't allowed to move it to another node"。
  可用性优先于过滤规则 —— 排查"filter 不生效"时先看这个。
- **voting configuration 保持奇数**。3 个 master 候选排除 1 个,投票配置变成 **1** 个而不是 2 个
  (2 个投票者需要两个都同意,严格劣于 1 个)。这也是"候选数要奇数"的另一面。
- **`deleteVotingConfigExclusions()` 默认 `wait_for_removal=true` 会挂住**,它等的是被排除的节点
  真正离开集群;节点还活着就会一直等到超时,并把 exclusion 留给下一个测试。

## 已经踩过的坑

搭骨架过程中真实撞到并已修掉的,留个记录免得重犯:

- **`@Document(indexName = "${...}")` 不生效**。Spring Data Elasticsearch 认 SpEL(`#{...}`),
  不认属性占位符。未解析的字面量会原样拼进 URL,ES 返回 400
  (`[es/indices.exists] Expecting a response body`)。现在走
  [`IndexNames`](middleware-lab-elasticsearch/src/main/java/com/pacvue/lab/es/config/IndexNames.java) bean。
- **日期只声明一种 format 会在读取时炸**。`@Field(format = DateFormat.date_time)` 解析不了
  没有毫秒的 `2026-09-17T00:00:00Z`,而共享索引里别的服务写出这种格式太常见了。
  `DateFormat.date_optional_time` 也救不了,得显式给 `pattern`。
- **父 pom 里要显式声明 `spring-boot-maven-plugin`**。`spring-boot-starter-parent` 只给
  pluginManagement,不会自动绑 `repackage`,产出的 jar 没有 `Main-Class`,
  容器里报 `no main manifest attribute`。
- **compose healthcheck 里的 URL 必须加引号**,否则 `?a=1&b=2` 的 `&` 会被 shell 当成后台执行。
