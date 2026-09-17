# 用容器跑起来 & 看内部细节

> 本文件里的命令都在**本模块目录**下执行:`cd middleware-lab-mysql`。
> 每个中间件模块自带 Dockerfile、compose 和脚本,compose 的 project name 是 `lab-mysql`
> / `lab-mysql-repl`,和 ES 那套(`lab-elasticsearch`)互不干扰,可以同时开着。
>
> Docker Engine 装在 WSL2 的 Ubuntu-24.04 里(不是 Docker Desktop),命令都在 WSL 里跑。
> 项目在 Windows 侧时,WSL 里的路径形如
> `/mnt/c/Users/<用户名>/minxc98-project/db-middleware-lab`。

## 启动

```bash
docker compose up -d --build          # MySQL + lab 应用
docker compose --profile ui up -d     # 额外带上 Adminer(localhost:8082)
docker compose ps
```

- MySQL → `localhost:3306`,账号 `root` / `labroot`
- lab 应用 → http://localhost:8081/api/mysql/ping、/actuator/health
- 数据落在命名卷 `lab-mysql-data`
- 两个库:`lab_mysql`(应用的)、`lab_mysql_it`(集成测试的,互不干扰)

只要 MySQL 不要应用:`docker compose up -d mysql`。这也正是跑测试前唯一需要做的事。

镜像单独构建。注意 **build context 是仓库根**(多模块 Maven 构建需要父 pom 和 common 模块),
Dockerfile 才在模块里:

```bash
cd ..   # 仓库根
docker build -f middleware-lab-mysql/Dockerfile -t lab/mysql:dev .
```

### 为什么配置是 compose 里的一堆 `--flag`,而不是挂一个 my.cnf

挂进去的 my.cnf **会被静默忽略**。`/mnt/c` 在 WSL2 下是 0777,bind mount 进容器还是 0777,
而 MySQL 拒绝读取 world-writable 的配置文件,只在日志里留一行:

```
mysqld: [Warning] World-writable config file '/etc/mysql/conf.d/lab.cnf' is ignored.
```

结果是**服务器跑在全默认配置上**,而你以为配置生效了。compose 的 `configs:` 段也一样(实测
仍然是 0777)。命令行参数不会被忽略,所以 lab 的配置全部走 `command:`,
`MysqlContainerFactory.MYSQLD_FLAGS` 里是同一份,供 Testcontainers 路径使用。

## 一把梭:看整体状况

```bash
./scripts/seed.sh                     # 先灌 10 万行(可给个数字改数量)
./scripts/mysql-inspect.sh            # 全部
./scripts/mysql-inspect.sh disk locks # 只看磁盘和锁
```

七个小节,对应七类问题:

| 小节 | 回答什么 |
| --- | --- |
| `status` | 版本/隔离级别/连接数、QPS 构成、当前谁连着在跑什么 |
| `disk` | 数据目录占多少、最大的 20 个文件、每张表的数据/索引/碎片空间 |
| `tables` | 引擎、行格式、行数估算、自增值、每张表的 `.ibd` 路径与大小 |
| `indexes` | 每个索引的列序与 cardinality(选择性)、从没被用过的索引、在做全表扫的语句 |
| `locks` | 当前打开的事务及其年龄、**每把行锁的 LOCK_MODE**、谁在等谁、MDL、最近一次死锁 |
| `binlog` | binlog 文件列表与当前位点、format/row_image/sync_binlog、当前文件最后 20 条事件 |
| `innodb` | Buffer Pool 冷热页与命中率、LSN 与 checkpoint 的差、undo history 长度 |

`locks` 小节里 `LOCK_MODE` 这一列是整章锁机制的入口:

```
X,REC_NOT_GAP        记录锁,只锁这一行
X,GAP                间隙锁,只锁区间不锁行
X                    临键锁 = 间隙 + 行,即 (前一行, 本行]
X,GAP,INSERT_INTENTION  一条被别人的间隙锁挡住的 INSERT
IX / IS              表级意向锁
```

`LOCK_DATA` 是被锁住的索引条目:主键值、或"二级索引值, 主键值",
`supremum pseudo-record` 表示一直延伸到索引末尾的那个间隙。

## 看文件:数据目录长什么样

```
/var/lib/mysql/
├── <库名>/<表名>.ibd     每张表一个表空间(innodb_file_per_table=ON)
├── ibdata1               系统表空间:数据字典、双写缓冲区(8.0 已拆出)等
├── undo_001 / undo_002   undo 表空间 —— 长事务撑大的就是这两个
├── #ib_redo*             redo log(固定容量、环形写)
├── binlog.NNNNNN         binlog(追加写,按 expire 时间清理)
├── mysql.ibd             数据字典
└── slow.log              慢查询日志(compose 里 long_query_time=1)
```

三种看法:

```bash
# 1. 直接进容器
docker exec -it lab-mysql bash
du -sh /var/lib/mysql/lab_mysql/*

# 2. 用 toolbox,只读挂载同一个卷(MySQL 停着也能看,带 tree / ncdu)
docker compose --profile tools run --rm toolbox
ncdu /mysql-data

# 3. 从宿主机看卷的实际位置
docker volume inspect lab-mysql-data
```

看"某张表到底占多少":

```bash
docker exec -it lab-mysql mysql -uroot -plabroot -e "
SELECT s.NAME, d.PATH, ROUND(s.FILE_SIZE/1024/1024,2) file_mb
FROM information_schema.INNODB_TABLESPACES s
JOIN information_schema.INNODB_DATAFILES d ON d.SPACE = s.SPACE
WHERE s.NAME LIKE 'lab_mysql/%' ORDER BY s.FILE_SIZE DESC"
```

`information_schema.TABLES` 里的 `DATA_LENGTH` 是聚簇索引(也就是数据本身),
`INDEX_LENGTH` 是所有二级索引之和,`DATA_FREE` 是删完没回收的碎片。

## 主从:另起一套两节点

```bash
docker compose -f docker-compose.replication.yml up -d
docker logs lab-mysql-repl-setup        # 一次性容器,把复制链路接起来
./scripts/replication-chaos.sh status
```

- source → `localhost:3307`,replica → `localhost:3308`
- GTID + `SOURCE_AUTO_POSITION=1`,半同步在两端都装好
- replica 是 `super_read_only`,并开了 4 线程并行回放

故意搞坏它:

```bash
./scripts/replication-chaos.sh lag          # 停回放线程 + 写入,看积压
./scripts/replication-chaos.sh resume
./scripts/replication-chaos.sh partition    # 断网,看半同步降级为异步
./scripts/replication-chaos.sh heal
./scripts/replication-chaos.sh kill-source  # 停主库,顺带打印提升从库的命令
./scripts/replication-chaos.sh watch        # 2 秒刷新一次
```

**`Seconds_Behind_Source` 在回放线程停掉时是 `NULL`,不是一个很大的数。**
只盯这个指标的监控,对"从库彻底不回放了"这种更严重的故障会完全没反应。
脚本里同时打印 `RECEIVED_TRANSACTION_SET` 减去 `gtid_executed` 的差集,那个才一直有效。

复制相关的测试用 `@RequiresReplicationTopology` 探测:**两节点没起就自动跳过**,
不会让 `mvn verify` 失败。

## 停掉

```bash
docker compose down                                   # 留数据
docker compose down -v                                # 连卷一起删
docker compose -f docker-compose.replication.yml down -v
```

## 常用排查命令

```bash
# 谁在跑什么、跑了多久
docker exec -it lab-mysql mysql -uroot -plabroot -e "SHOW FULL PROCESSLIST"

# 谁在等谁的锁
docker exec -it lab-mysql mysql -uroot -plabroot -e "SELECT * FROM sys.innodb_lock_waits\G"

# 最近一次死锁的完整现场
docker exec -it lab-mysql mysql -uroot -plabroot -e "SHOW ENGINE INNODB STATUS\G" \
  | sed -n '/LATEST DETECTED DEADLOCK/,/^---/p'

# 慢查询
docker exec -it lab-mysql tail -50 /var/lib/mysql/slow.log

# 容器层面的 CPU / 内存 / IO
docker stats lab-mysql --no-stream

# binlog 里到底记了什么(ROW 格式要加 -v 才能看到行镜像)
docker exec -it lab-mysql sh -c "mysqlbinlog -v --base64-output=DECODE-ROWS /var/lib/mysql/binlog.000003 | tail -60"
```
