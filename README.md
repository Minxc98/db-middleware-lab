# db-middleware-lab

用来验证各类数据库/存储中间件真实行为的实验场。每个中间件一个 Maven 模块,
测试环境由 Testcontainers 自动拉起,不依赖任何外部已部署的环境。

当前已落地:**Elasticsearch**、**MySQL**。

每个模块的测试都按对应的那套笔记逐章组织,一个用例钉住一条具体行为 —— 目标是
"笔记里写的那句话,在真实服务器上到底是不是这样",而不是覆盖率。

## 环境要求

| 项 | 版本 |
| --- | --- |
| JDK | 21 |
| Maven | 3.9+(IDEA 内置即可,当前机器未把 `mvn` 加到 PATH) |
| Docker | 跑集成测试必需(Testcontainers 用) |
| MySQL | 8.0.46(镜像版本在根 pom 的 `mysql.image`) |
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
├── middleware-lab-elasticsearch   ES 行为验证(容器环境自带,见其 DOCKER.md)
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
│
└── middleware-lab-mysql           MySQL/InnoDB 行为验证(见其 DOCKER.md)
    ├── Dockerfile                 多阶段构建(context 同样是仓库根)
    ├── docker-compose.yml         MySQL + lab 应用(+ Adminer / toolbox 两个可选 profile)
    ├── docker-compose.replication.yml  主从两节点(3307/3308)+ 一次性接线容器
    ├── docker/init/               首次启动建 lab_mysql 与 lab_mysql_it 两个库
    ├── docker/replication/setup-replication.sh  建复制账号、装半同步、CHANGE REPLICATION SOURCE
    ├── scripts/seed.sh            服务端递归 CTE 灌数据,不按行走网络
    ├── scripts/mysql-inspect.sh   状态/磁盘/表/索引/锁/binlog/InnoDB 七个小节
    ├── scripts/replication-chaos.sh  停回放、断网、停主库,看链路怎么反应
    ├── DOCKER.md                  容器化运行 & 内部观察手册
    ├── src/main/ MysqlLabApplication      手工探索用的 Boot 应用(测试不依赖它)
    │         config/MysqlLabProperties    lab.mysql.* 配置
    │         domain/OrderRow              自增主键 + 唯一键 + 三列联合索引的示例表
    │         domain/ExplainRow            EXPLAIN 一行的结构化形式(type/key/key_len/Extra)
    │         repository/OrderJdbcRepository  DDL 与批量写,偏移分页 vs 书签分页
    │         service/MysqlProbeService    MiddlewareProbe 的 MySQL 实现
    │         service/OrderSeedService     批量灌数据 + 每批耗时
    │         service/ExplainService       EXPLAIN / FORMAT=JSON / ANALYZE
    │         api/MysqlLabController       /api/mysql/** 手工触发
    └── src/test/ support/MysqlContainerFactory   决定连哪个 MySQL(同 ES 的三段式)
               support/Session                    **一个连接 = 一个会话**,并发用例的基础设施
               support/LockInspector              读 performance_schema.data_locks
               support/AbstractBehaviorIT         每个测试类一张表,每个用例前重建
               support/ReplicationCluster         两节点探测
               support/RequiresReplicationTopology  @Inherited 的启用条件(见"踩过的坑")
               behavior/{architecture,storage,index,transaction,lock,log,tuning,replication}/
```

用 plain JDBC 而不是 JPA 是刻意的:这个模块要观察的就是服务器实际做了什么
(锁模式、ReadView 生成时机、`Handler_read_*` 计数器),ORM 的会话缓存和生成的 SQL
会正好挡在中间。

## 跑起来

集成测试类以 `IT` 结尾,由 failsafe 在 `verify` 阶段执行;`mvn test` 只跑单元测试。

```bash
mvn -DskipTests package && mvn verify
```

单独跑一个:

```bash
mvn -pl middleware-lab-elasticsearch verify -Dit.test=ProductSearchIT
mvn -pl middleware-lab-mysql -am verify -Dit.test=RowLockShapeIT
```

(只跑单个模块时要带 `-am`,否则解析不到 `middleware-lab-common`。)

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

### 测试连的是哪个 MySQL

一模一样的三段式,系统属性换成 `lab.mysql.url` / `lab.mysql.autodetect`,探测端口 3306。
区别是**探测更严格**:必须是一个已经有 `lab_mysql_it` 库、且能读
`performance_schema.data_locks` 的服务器才会被复用 —— 别人机器上那个跑着业务的 MySQL
不会被误当成 lab 环境往里建表。

```bash
cd middleware-lab-mysql && docker compose up -d mysql   # 跑测试前唯一要做的事
```

复制相关的 11 个用例需要另一套两节点环境,没起就**自动跳过**:

```bash
docker compose -f docker-compose.replication.yml up -d
```

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

MySQL 同理:

```bash
cd middleware-lab-mysql
docker compose up -d --build
./scripts/seed.sh                     # 服务端 CTE 灌 10 万行
./scripts/mysql-inspect.sh            # 状态/磁盘/表/索引/锁/binlog/InnoDB
```

主从相关的行为(复制延迟、半同步降级、GTID 续传)需要两节点,另起一套:

```bash
docker compose -f docker-compose.replication.yml up -d   # source 3307 / replica 3308
./scripts/replication-chaos.sh status
./scripts/replication-chaos.sh lag                       # 停回放线程,看积压
```

容器环境跟模块走:每个中间件模块自带 Dockerfile、compose 和脚本,compose 的 project name
各不相同(`lab-elasticsearch` / `lab-mysql` / `lab-mysql-repl`),几个 lab 可以同时开着。
完整说明见各模块的 DOCKER.md:
[Elasticsearch](middleware-lab-elasticsearch/DOCKER.md)(数据目录布局、段文件后缀、水位线触发
只读的复现)、[MySQL](middleware-lab-mysql/DOCKER.md)(数据目录布局、`LOCK_MODE` 各个取值怎么读、
binlog 解码、主从故障注入)。

## 测试覆盖地图 —— Elasticsearch

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

## 测试覆盖地图 —— MySQL

同样按 Dropbox 里那套 mysql 笔记逐章走,**121 个用例全部跑通**(其中 11 个需要主从两节点,
没起就跳过)。加粗的是"跑之前以为是另一回事"的那些。

| 笔记章节 | 测试类 | 钉住的行为 |
| --- | --- | --- |
| 01 §1-3 | `architecture/ServerArchitectureIT` | 8.0 的查询缓存是**被删掉**不是被关掉(变量一个都不剩);InnoDB 是 DEFAULT 引擎;**binlog 属于 Server 层 → 往 MyISAM 表写也进 binlog**;EXPLAIN 只走到优化器就停 |
| 01 §4 | `architecture/EngineComparisonIT` | MyISAM 不认 ROLLBACK;**gtid_mode=ON 下同一个事务不能同时写事务表和非事务表**;MyISAM 在 data_locks 里一把锁都没有;MyISAM 行数是元数据、InnoDB 得数 |
| 02 §1-3 | `storage/RowFormatAndOverflowIT` | 页 16KB、默认 DYNAMIC;每表一个 `.ibd` 且大小是页的整数倍;**16×VARCHAR(1000) 的表在 COMPACT 下建表就被拒(768 字节前缀留在页内),DYNAMIC 下没问题**;无主键表有 `GEN_CLUST_INDEX` |
| 02 §4 | `storage/BufferPoolIT` | `innodb_old_blocks_pct=37` / `_time=1000`;**冷区页数确实约占 LRU 的 37%**;逻辑读远多于物理读;POOL_SIZE 比 256M/16K 少一页;change buffering 只对非唯一二级索引 |
| 03 §2-3 | `index/ClusteredIndexIT` | **二级索引叶子物理上带着主键 —— 所以 `SELECT id WHERE order_no=?` 是覆盖索引**;多要一个非索引列就要回表;主键等值是 const;DATA_LENGTH 就是聚簇索引 |
| 03 §3,§5 | `index/CoveringIndexAndIcpIT` | `Using index`(覆盖)和 `Using index condition`(ICP)是两回事;关掉 ICP 后索引和 key_len 不变,条件上移到 Server 层 |
| 03 §4 | `index/LeftmostPrefixIT` | key_len **8 / 9 / 16** 精确对应用到了几列;**跳过中间列时 key_len 只有 8,但第三列仍被 ICP 下推** —— 最左前缀决定怎么"定位",ICP 决定怎么"过滤";范围之后的列失效 |
| 03 §6 | `index/IndexInvalidationIT` | 函数包列;**字符串列 = 数字字面量时转换的是列不是值**;前导 `%`;OR 非索引列;**8.0 函数索引把第一种救回来** |
| 03 §7 | `index/PrimaryKeyChoiceIT` | 4 万行下 UUID 主键的**聚簇索引和二级索引都更大**(页分裂 + 36 字节主键被每个二级索引叶子复制一份);自增值在 information_schema 里可见 |
| 04 §2 | `transaction/IsolationLevelIT` | 默认 RR(不是标准的 RC);RU 脏读且可能读到从未存在的值;RC 不可重复读;RR 可重复 |
| 04 §3.3 | `transaction/ReadViewTimingIT` | **RR 的快照在第一次快照读时才生成,不是 `START TRANSACTION` 时** —— "事务看到的是它开始那一刻的库"是错的;`WITH CONSISTENT SNAPSHOT` 才是立刻;RC 每条语句一个 |
| 04 §4 | `transaction/SnapshotVsCurrentReadIT` | 同一事务里普通 SELECT 和 FOR UPDATE 给出不同答案;**`UPDATE x = x + 1` 基于最新版本算,结果是 201 而不是 101**;快照读一把锁都不加 |
| 04 §5 | `transaction/PhantomReadIT` | 快照读无幻读;当前读靠间隙锁把 INSERT 挡在外面;RC 直接放行;**被 UPDATE 碰过的"幻行"就变得可见了**(笔记里那个残留特例) |
| 05 §3-4 | `lock/RowLockShapeIT` | 唯一索引等值命中 → `X,REC_NOT_GAP`;未命中 → `X,GAP`;普通索引等值 → 临键锁 + 后一个间隙锁 + 主键记录锁,三把;范围;`supremum pseudo-record` |
| 05 §3 | `lock/LockRequiresIndexIT` | 无索引条件锁住 4 行 + supremum 全部临键锁(=整表);不相干的行也被挡住;**锁落在哪个索引取决于执行计划,不是 WHERE 写了什么** |
| 05 §4 | `lock/GapLockByIsolationIT` | RR 有间隙锁 / RC 只有 `REC_NOT_GAP`;被挡住的 INSERT 是 `X,GAP,INSERT_INTENTION` + WAITING;**两个事务可以同时持有同一个间隙的排他锁** |
| 05 §2 | `lock/IntentionLockIT` | 行锁必然伴随表级 IX;`FOR SHARE` 是 IS;IX 之间互相兼容;`LOCK TABLES WRITE` 靠 IX 就能 O(1) 拒绝 |
| 05 §5 | `lock/DeadlockIT` | 反序加锁 → 1213,不等超时;**输的一方整个事务回滚,不只是那条语句**;`SHOW ENGINE INNODB STATUS` 留现场 |
| 05 §6 | `lock/MetadataLockIT` | 事务读表就持 MDL;**DDL 排队后,后来的普通 SELECT 也一起被堵死**;`lock_wait_timeout` 默认 31536000 秒 = 365 天,而且和 `innodb_lock_wait_timeout` 不是一回事 |
| 06 §4 | `log/BinlogFormatIT` | ROW/FULL/`sync_binlog=1`;**STATEMENT 格式下 `INSERT ... UUID()` 报 1592 "在副本上可能返回不同的值"**;ROW 记 `Update_rows` 行镜像 vs STATEMENT 记 SQL 原文;轮转不覆盖 |
| 06 §2,§5 | `log/RedoAndBinlogIT` | **提交推进 redo 和 binlog 两个日志,回滚只推进 redo** —— 这就是两阶段提交要保证的东西的外在表现;LSN 领先 checkpoint(WAL);redo 有容量、binlog 有过期时间 |
| 06 §3 | `log/UndoPurgeIT` | `trx_rows_modified` 是待回滚的量;50 个版本之后老 ReadView 仍读到 v0;**长事务把 history list 顶住不降**;**空闲服务器上 purge 几乎不动** |
| 08 §3 | `tuning/ExplainIT` | const/ref/range/index/ALL 五级;join 到唯一键是 eq_ref;`Using filesort` 与索引顺序;`Using temporary` 与 GROUP BY;`EXPLAIN ANALYZE` 是真跑 |
| 08 §4.1 | `tuning/DeepPaginationIT` | **`LIMIT 9000,10` 读了 9040 行,书签法读 9 行**(`Handler_read_next` 计数,不受缓存影响);偏移越深越贵、书签法恒定;延迟关联把偏移付在覆盖索引上 |
| 08 §4.2 | `tuning/CountIT` | `COUNT(*)` 选最小的二级索引扫;`COUNT(*)`/`COUNT(1)`/`COUNT(主键)` 完全一致,`COUNT(可空列)` 是**另一个问题**;EXPLAIN 的 rows 是估算 |
| 08 §5 | `tuning/PartitionPruningIT` | 带分区键只扫一个分区,不带就扫全部(**比不分区更慢**);`DROP PARTITION` 是元数据操作;**主键必须包含分区列(1503)** |
| 07 §1,§3 | `replication/ReplicationTopologyIT` | IO/SQL 两个线程独立;写入传播;`super_read_only` 连 root 都挡;GTID 集合里有 source 的 uuid;`log_replica_updates` 决定能否被提升为主 |
| 07 §4-5 | `replication/ReplicationLagIT` | **回放线程停掉时 `Seconds_Behind_Source` 是 NULL 而不是一个大数字**;真正有效的信号是"收到的 GTID 减去执行过的";`WAIT_FOR_EXECUTED_GTID_SET` 解决写后读;并行回放 |
| 07 §2 | `replication/SemiSyncIT` | 两端 ON;每次提交都被 ack;**没人 ack 时等够 timeout 就自动降级成异步并照常提交** —— 保证没了,应用侧毫无感知 |

### 跑测试时发现的、和笔记出入的地方(MySQL)

- **`Seconds_Behind_Source` 在 SQL 线程停掉时是 `NULL`**。它由回放线程根据正在处理的事件
  时间戳算出来,线程停了就没得算。只对这个指标做告警的监控,恰恰对"从库彻底不回放"这种
  更严重的故障完全沉默。真正一直有效的是
  `GTID_SUBTRACT(RECEIVED_TRANSACTION_SET, @@global.gtid_executed)`。
- **空闲服务器上 purge 几乎不动**。长事务提交后 history list 在原地停了 30 秒纹丝不动,
  要有新的写入把 purge 线程"叫醒"才开始回收。也就是说:一个长事务结束后系统正好安静下来,
  它撑大的 undo 会留在那儿相当久。
- **`Rpl_semi_sync_source_clients` 不会因为从库断开而立刻归零**。主库把已断开从库的 dump
  线程留了一会儿,这个计数回答的是"挂了几个从库",不是"下一次提交会有几个 ack"。
- **行格式超限是在建表时就被拒的,不是插入时**。16 个 `VARCHAR(1000)` 在 `ROW_FORMAT=COMPACT`
  下 `CREATE TABLE` 直接报 1118,MySQL 不需要看到数据就知道没有哪一行放得下。
- **加锁落在哪个索引,取决于执行计划**。`WHERE id >= 10 AND id < 16 FOR UPDATE` 在只 SELECT
  id 时会走覆盖的二级索引,锁就加在那个索引上。从 WHERE 子句出发排查锁冲突会看错对象,
  要从 EXPLAIN 出发。
- **`lock_wait_timeout` 和 `innodb_lock_wait_timeout` 是两个东西**。前者管 MDL、默认 365 天;
  后者管行锁、默认 50 秒。被长事务堵住的 ALTER 用的是前者 —— 所以它会一直等下去。
- **GTID 开着时,一个事务不能同时改事务表和非事务表**。直接报
  `Statement violates GTID consistency`。混合引擎的库和 GTID 复制装不到一起。
- **Buffer Pool 的 POOL_SIZE 比理论值少一页**(256M/16K = 16384,实际 16383),
  差的那一页是它自己的控制结构。

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

搭 MySQL 模块时新踩到的,其中前三个都属于"**测试基础设施骗了我**"这一类 —— 现象出现在
别的用例上,原因在很远的地方:

- **`@EnabledIf` 不会被继承**。把它放在 `AbstractClusterIT` / `AbstractReplicationIT` 上
  *看起来*生效了,实际上对子类完全没作用:集群没起时它们照跑不误,然后连接失败报 error,
  而不是 skip。ES 模块原来就是这个写法,README 里"集群没起就自动跳过"这句话一直是错的。
  两个模块现在都改成自己定义一个带 `@Inherited` 的组合注解
  (`RequiresThreeNodeCluster` / `RequiresReplicationTopology`)。
- **用 SQL 字符串管事务,会把未提交事务连同锁一起还进连接池**。`Session` 最初用
  `START TRANSACTION` / `COMMIT` 字符串,JDBC 层的 `autoCommit` 还是 `true`,于是驱动和
  连接池都以为这个连接是空闲的:关闭时不回滚,连接带着开着的事务和它持有的锁回到池里,
  被下一个借用者拿走。现象是 RR 不可重复、间隙锁不挡插入、以及别处莫名其妙的 60 秒超时。
  改成 `setAutoCommit(false)` + `connection.commit()` 之后全部消失。事务必须对管理连接的
  那一层可见。
- **`SET SESSION` 同样会泄漏**。一个用例把 `lock_wait_timeout` 设成 1 秒,连接回池,
  几分钟后另一个用例的 ALTER 莫名其妙 1 秒就超时。`Session#sessionVariable` 现在会记下来
  并在关闭时还原 —— 而且**还原不能把原值当字符串传回去**:
  `SET SESSION lock_wait_timeout = '31536000'` 会失败,于是"还原"静默地没生效,泄漏照旧。
  现在用 `SET @saved := @@session.x` 把原值存在用户变量里,类型原样带回。
- **挂进容器的 my.cnf 会被静默忽略**。`/mnt/c` 下的文件是 0777,MySQL 拒绝读 world-writable
  的配置文件,只留一行 warning,服务器跑在全默认配置上。compose 的 `configs:` 段也一样。
  所有配置改走命令行参数。
- **`rpl_semi_sync_source_timeout` 不能当启动参数**。它属于插件,插件没装载时 mysqld 见到
  未知变量会直接拒绝启动。得在装完插件之后 `SET GLOBAL`。
- **主从两端都设 `MYSQL_DATABASE` 会让复制从第一条语句就断**。两边各自用自己的 GTID 建了
  同名库,主库那条 `CREATE DATABASE` 到从库上就执行失败。让库只在主库上建、通过复制流过去。
- **Connector/J 默认不会真的批量发送**。没有 `rewriteBatchedStatements=true` 时
  `batchUpdate` 是一行一条 INSERT,灌 2 万行要 55 秒;打开之后同样的数据 1 秒出头。
  这也让"批量写入很快"这个印象在没配这个参数时完全不成立。
