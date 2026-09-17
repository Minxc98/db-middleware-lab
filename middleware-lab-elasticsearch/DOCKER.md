# 用容器跑起来 & 看内部细节

> 本文件里的命令都在**本模块目录**下执行:
> `cd middleware-lab-elasticsearch`。每个中间件模块自带 Dockerfile、compose 和脚本,
> compose 的 project name 是 `lab-elasticsearch`,和别的模块互不干扰。
>
> Docker Engine 29.8.1 装在 WSL2 的 Ubuntu-24.04 里(不是 Docker Desktop),命令都在
> WSL 里跑。项目在 Windows 侧时,WSL 里的路径形如 `/mnt/c/Users/<用户名>/IdeaProjects/db-middleware-lab`。
> 细节见最后一节。

## 启动

```bash
docker compose up -d --build          # ES + lab 应用
docker compose --profile ui up -d     # 额外带上 Kibana(localhost:5601)
docker compose ps
```

- Elasticsearch → http://localhost:9200
- lab 应用 → http://localhost:8080/api/es/ping、/actuator/health
- 数据落在命名卷 `db-middleware-lab_es-data`

只要 ES 不要应用:`docker compose up -d elasticsearch`。

镜像单独构建。注意 **build context 是仓库根**(多模块 Maven 构建需要父 pom 和 common 模块),
Dockerfile 才在模块里:

```bash
cd ..   # 仓库根
docker build -f middleware-lab-elasticsearch/Dockerfile -t lab/es:dev .
```

`.dockerignore` 也因此留在仓库根 —— 它约束的是 context,不属于某个模块。

## 一把梭:看整体状况

```bash
./scripts/seed.sh                    # 先灌 4 万条测试数据(可给个数字改数量)
./scripts/es-inspect.sh              # 全部
./scripts/es-inspect.sh disk files   # 只看磁盘和文件
```

七个小节,对应七类问题:

| 小节 | 回答什么 |
| --- | --- |
| `health` | 集群/节点是否正常,有没有 unassigned 分片、堆积的 pending task |
| `disk` | 每个节点占了多少磁盘、还剩多少、三条水位线当前设置 |
| `indices` | 各索引的文档数、删除数、store 大小,分片落在哪 |
| `segments` | 段文件数量、已删除文档仍占的空间、translog / merge / refresh 统计 |
| `files` | 容器里数据目录的真实文件:目录结构 + 最大的 20 个文件 |
| `stats` | 容器层面的 CPU / 内存 / 网络 / 块设备 IO,以及卷的宿主机路径 |
| `jvm` | 堆占用、GC、线程池排队与拒绝、熔断器是否跳闸 |

## 看文件:数据目录长什么样

ES 的数据布局:

```
/usr/share/elasticsearch/data/
└── indices/<index-uuid>/<shard-number>/
    ├── index/        Lucene 段文件(真正的数据)
    ├── translog/     写入后尚未 flush 的事务日志
    └── _state/       分片元数据
```

三种看法:

```bash
# 1. 直接进 ES 容器
docker exec -it lab-elasticsearch bash
du -sh /usr/share/elasticsearch/data/indices/*

# 2. 用 toolbox,只读挂载同一个卷(ES 停着也能看,带 tree / ncdu)
docker compose --profile tools run --rm toolbox
ncdu /es-data          # 交互式浏览磁盘占用

# 3. 从宿主机看卷的实际位置
docker volume inspect lab-elasticsearch-data
```

段文件后缀对照(排查"为什么占这么大"时有用):

| 后缀 | 内容 |
| --- | --- |
| `.cfs` / `.cfe` | 复合段,小段会被打包成一个文件 |
| `.fdt` / `.fdx` | 存储字段(`_source` 在这里) |
| `.tim` / `.tip` | 倒排索引的词典 |
| `.doc` / `.pos` | 倒排表、词位置 |
| `.dvd` / `.dvm` | doc values(聚合、排序用) |
| `.liv` | 标记已删除的文档 —— 删除不立刻回收空间,要等段合并 |
| `translog-*.tlog` | 事务日志 |

想直观看到"删除不等于释放":写一批文档 → `_cat/segments` 看 `docs.deleted` → `POST /index/_forcemerge?only_expunge_deletes=true` → 再看一次。

## 看磁盘:水位线行为

三条线写在 `docker-compose.yml` 里(就是默认值,显式列出来方便改):

- 85% low —— 不再往该节点分配新分片
- 90% high —— 已有分片开始往别处搬(单节点无处可搬)
- 95% flood —— **索引被置为 read-only-allow-delete**,写入直接报错

想复现 flood 触发,把阈值调低再灌数据:

```bash
curl -X PUT localhost:9200/_cluster/settings -H 'Content-Type: application/json' -d '{
  "transient": {"cluster.routing.allocation.disk.watermark.flood_stage": "1%"}
}'
# 触发后索引变只读,恢复:
curl -X PUT 'localhost:9200/_all/_settings' -H 'Content-Type: application/json' \
  -d '{"index.blocks.read_only_allow_delete": null}'
```

## 看运行状况:常用 docker 命令

```bash
docker compose logs -f elasticsearch      # 日志(ES 启动失败基本都能在这里看到原因)
docker stats                               # 实时 CPU / 内存 / IO
docker top lab-elasticsearch               # 容器内进程
docker exec lab-elasticsearch ps aux
docker diff lab-elasticsearch              # 容器文件系统相对镜像的改动
docker inspect lab-elasticsearch \
  --format '{{json .HostConfig.Memory}} {{json .Mounts}}'
docker exec lab-elasticsearch cat /proc/1/limits     # 实际生效的 ulimit
docker exec lab-elasticsearch free -m                # 容器看到的内存
```

ES 自身的接口(`_cat` 系列后面加 `?v` 显示表头,加 `?help` 列出所有可用列):

```bash
curl 'localhost:9200/_cat/indices?v&s=store.size:desc'
curl 'localhost:9200/_cat/shards?v'
curl 'localhost:9200/_cat/thread_pool?v&h=node_name,name,active,queue,rejected'
curl 'localhost:9200/_nodes/hot_threads'          # CPU 被什么吃掉了
curl 'localhost:9200/_cat/segments?v&help'
```

## 3 节点集群

单节点看不到的东西(选主、quorum、副本放置、分片迁移、冷热分层)需要真集群:

```bash
docker compose -f docker-compose.cluster.yml up -d
curl localhost:9201/_cat/nodes?v
```

- 三个节点 es01/es02/es03,端口 **9201/9202/9203**,project name `lab-es-cluster`
- **和单节点那套并存**(端口和 project name 都不冲突),想省内存就只开一套
- 三个都是 master-eligible → **quorum = 2**,挂一个还能选出 master
- 节点带 `node.attr.tier` = hot / warm / cold,用来演示 allocation filtering(ILM 分层的底层机制)
- 每个节点 512m heap,容器上限 1.5g,三个加起来约 4.5g

跑集群相关的测试:

```bash
cd .. && mvn verify -Dlab.es.cluster.uri=http://localhost:9201
```

不指定也行,默认就是 9201。**集群没起时这些测试自动跳过**(`@EnabledIf` 探测),
不会让构建失败。

### 故意搞坏它

有些行为只能靠停节点/断网触发,`scripts/cluster-chaos.sh` 把这些操作和观察点放在一起:

```bash
./scripts/cluster-chaos.sh status            # 节点、健康、分片分布
./scripts/cluster-chaos.sh kill es02         # 停一个节点 → 看副本升主
./scripts/cluster-chaos.sh start es02        # 回来 → 副本重建 → green
./scripts/cluster-chaos.sh restart es03      # 看 peer recovery 的 stage 变化
./scripts/cluster-chaos.sh partition es03    # 断网 → 少数派选不出 master
./scripts/cluster-chaos.sh heal es03         # 恢复
./scripts/cluster-chaos.sh watch             # 每 2 秒刷新健康和分片
```

几个值得盯着看的:

| 操作 | 看什么 | 预期 |
| --- | --- | --- |
| `kill es02` | `_cat/shards` 的 `prirep` 列 | 某个 `r` 变成 `p`,集群 yellow,读写不中断 |
| `restart es03` | `_cat/recovery?v&active_only=true` | stage: INDEX(拷段) → TRANSLOG(重放) → DONE |
| `partition es03` | 多数派 vs 少数派的 `_cluster/health` | 多数派正常;少数派 `master_not_discovered_exception`,**不会出现第二个 master** |

### 停掉

```bash
docker compose -f docker-compose.cluster.yml down     # 保留数据卷
docker compose -f docker-compose.cluster.yml down -v  # 连数据一起删
```

## 清理

```bash
docker compose down            # 停容器,保留数据卷
docker compose down -v         # 连数据卷一起删
```

## 关于这台机器上的 Docker

Docker Engine 装在 WSL2 的 Ubuntu-24.04 里(apt 官方仓库,GPG 验签),不是 Docker Desktop:

- 版本:Engine 29.8.1 / Compose v5.5.1 / buildx 0.37.1
- 开机自启:`systemctl enable --now docker` 已执行
- `vm.max_map_count=262144` 已写入 `/etc/sysctl.d/99-elasticsearch.conf`(ES 启动的硬性要求)
- 当前用户已加入 `docker` 组

**注意**:加组之后需要重开登录会话才生效。在 PowerShell 里执行一次:

```powershell
wsl --shutdown
```

再进 WSL 就能免 sudo 用 docker 了。不想重开的话,当前会话里用 `sg docker -c "docker ps"`。

从 Windows 侧进去:

```powershell
wsl -d Ubuntu-24.04
cd /mnt/c/Users/<用户名>/IdeaProjects/db-middleware-lab   # 或仓库实际所在路径
```

端口是转发到 Windows 的,`localhost:9200` / `localhost:8080` 在 Windows 浏览器里直接能开。

跨 `/mnt/c` 做 docker build 会慢一些(Windows 文件系统走 9p 协议);数据卷是命名卷,
落在 WSL 自己的 ext4 上,不受影响。嫌构建慢可以把仓库放到 WSL 的 `~/` 下。

卸载:`sudo apt-get purge docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin`
