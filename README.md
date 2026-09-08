# 高并发健康社交平台 · 后端核心模块

> Spring Boot **4.1.1 GA**（Jakarta EE）+ **JDK 17** + Maven
> 覆盖四大核心模块：热点探测与多级缓存、亿级点赞削峰、推拉结合 Feed 流、分级限流与打赏最终一致性。

---

## 一、技术栈

| 组件 | 选型 | 说明 |
|---|---|---|
| 框架 | Spring Boot 4.1.1 (Jakarta EE 11) | Web / AOP / Scheduling |
| AOP | spring-boot-starter-**aspectj** | SB4 已移除 `spring-boot-starter-aop` |
| JSON | Jackson 2（`jackson-databind` + `jsr310`） | SB4 默认切到 Jackson 3，Jackson 2 需显式声明 |
| 缓存本地层 | Caffeine 3.2.4 | TTL 5 分钟，TinyLFU 淘汰 |
| 缓存分布层 | Redis（Lettuce，spring-boot-starter-data-redis） | 二级缓存 + 计数 + ZSet |
| 分布式工具 | Redisson 4.7.0 | 缓存重建分布式锁（single-flight） |
| 消息 | RabbitMQ（spring-boot-starter-amqp） | 点赞事件削峰、延迟重试、死信 |
| 数据库 | MySQL 8 + MyBatis-Plus 3.5.17 | 批量 upsert / 批量 update |
| 构建 | Maven | `mvn -DskipTests package` |

> ⚠️ **版本说明（重要，均为实测后调整）**
> 1. 提示词中的 `mybatis-plus-boot-starter` 与 Spring Boot 4 **二进制不兼容**
>    （其 POM 内部 import 的是 `spring-boot-dependencies:2.7.18`）。
>    改用 Baomidou 官方为 Spring Boot 4 发布的 **`mybatis-plus-spring-boot4-starter:3.5.17`**，
>    API 与用法完全一致（`@TableName` / `BaseMapper` / `LambdaQueryWrapper` 均不变）。
> 2. MP 3.5.9+ 将 `PaginationInnerInterceptor` 拆到 **`mybatis-plus-jsqlparser`** 模块，需显式引入。
> 3. Spring Boot 4 已**移除** `spring-boot-starter-aop`，改名为 `spring-boot-starter-aspectj`。
> 4. Spring Boot 4 默认使用 **Jackson 3**（`tools.jackson`），不再传递 `com.fasterxml.jackson`，
>    本项目显式引入 Jackson 2（`jackson-databind` + `jackson-datatype-jsr310`），
>    版本由 SB 的 `jackson-2-bom:2.21.5` 托管。
> 5. **不要加 `@EnableCaching`**：本项目手写多级缓存（Caffeine + Redis），
>    没有用 Spring Cache 抽象，因此不需要 `CacheManager` bean。
>    若误加 `@EnableCaching` 又没提供 `CacheManager`，启动会抛
>    `NoSuchBeanDefinitionException: No qualifying bean of type 'CacheManager'`。
> 6. **Spring AMQP 4.x 的 `Jackson2JsonMessageConverter` 信任包是「精确包名匹配」，不是前缀/通配**。
>    它先对类名取 `ClassUtils.getPackageName()`（如 `com.health.social.like.LikeEvent` →
>    `com.health.social.like`），再与信任列表做 `equals` 比较。因此
>    `setTrustedPackages("com.health.social.*")` **永远匹配不到子包类**，反序列化直接抛
>    `IllegalArgumentException: ... is not in the trusted packages`。
>    本项目用 `PrefixTrustedClassMapper`（覆写 public 的 `toJavaType`）做前缀匹配解决，
>    信任 `com.health.social` 整棵子树，对新增消息包天然兼容。

**编译状态：`mvn -B -DskipTests package` → BUILD SUCCESS**（JDK 17.0.11 / Maven 3.9.8）

---

## 二、快速开始

```bash
# 1. 初始化数据库
mysql -uroot -p < src/main/resources/db/schema.sql

# 2. 修改配置
#    src/main/resources/application.yml 中的 MySQL / Redis / RabbitMQ 地址

# 3. 编译 & 启动
mvn -DskipTests package
java -jar target/health-social.jar
```

### 接口速览

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/article/{id}` | 文章详情（挂 `@HotDetect` + `@RateLimit`） |
| POST | `/article` | 发布文章（触发 Feed 推/拉） |
| POST | `/like/{articleId}?op=1` | 点赞 / 取消（`op=0` 取消） |
| GET | `/like/count/{articleId}` | 点赞数（读 Redis，实时） |
| GET | `/feed?cursor=&size=20` | 首页时间线（推拉归并） |
| POST | `/follow/{id}` / DELETE | 关注 / 取关 |
| POST | `/donate` | 公益打赏（本地事务表） |
| POST | `/cache/warmup?topN=100` | **缓存预热接口** |
| GET | `/cache/stats` | 各级缓存命中率、刷盘批次 |
| GET | `/cache/hot?topN=100` | HeavyKeeper 热点榜 |
| POST | `/cache/like/flush` | 手动触发点赞刷盘 |

调试用请求头：`X-User-Id`（默认 20001）、`X-User-Level`（0 普通 / 1 认证医生 / 2 明星医生）。

---

## 三、模块详解

### 3.1 热点探测与多级缓存

```
请求 → @RateLimit（限流）→ @HotDetect（旁路统计）
                                  │
                                  ▼
                Caffeine L1（TTL 5min，命中率目标 ≥85%）
                                  │ miss
                                  ▼
                         Redis L2（TTL 10min + 抖动）
                                  │ miss
                                  ▼
                Redisson 分布式锁（single-flight）→ MySQL
```

**HeavyKeeper 热点探测** `cache/HeavyKeeperDetector.java` + `lua/heavy_keeper.lua`

- 结构：Redis Hash 存桶数组（`count * 2^32 + fingerprint`），Redis ZSet 存 TopK 榜单；
  两个 key 使用相同 hash tag `{article}`，Cluster 下同 slot，一段 Lua 原子完成。
- 算法：命中 → `count+1`；冲突 → 以概率 `1/2^count` 衰减，衰减到 0 则抢占该桶。
- 与 Count-Min Sketch / PFCOUNT 的差异（见类注释）：
  - PFCOUNT 只能算基数，给不出单对象的频次；
  - Count-Min 只增不减，冷数据长期霸榜，新热点挤不进来；
  - HeavyKeeper 指数衰减让"大象流"快速登顶，d=4 / w=100000 约 3.2MB 即可高精度识别 Top100。
- 切面 `HotArticleDetectAspect`：SpEL 从方法参数提取 ID，**异步**上报（线程池打满才回落到主线程），
  支持 `health.hot.sample-rate` 采样；任何异常都降级，绝不拖垮主链路。

**CacheService 多级缓存** `cache/CacheService.java`

本地命中率 85% 的四层保障：

1. 回源即回填 L1，热点数据天然常驻；
2. Caffeine **TinyLFU** 淘汰策略，高频条目几乎不会被冷数据挤掉；
3. **主动保活**：`CacheWarmupService` 每 30s 拉取 HeavyKeeper TopN，对榜单内 key 提前重建（refreshAhead），
   让热点 TTL 永不真正到期 —— 这是把理论命中率推到 85%+ 的关键，也消除了"热点同时过期 → Redis 雪崩"；
4. **预热接口**：`POST /cache/warmup` 灌满 L1 + L2，并通过 Redis Pub/Sub 广播让集群所有节点一起预热。

一致性：更新走 Cache-Aside（先删 Redis，再 Pub/Sub 广播清各节点本地缓存），
最坏不一致窗口 = L1 TTL（5 分钟）。缓存穿透用空值标记（TTL 120s）防御。

### 3.2 原子点赞与异步削峰

```
客户端 → LikeService.toggle()
            │  Lua（原子）
            ├─ SADD user:like:set:{uid}      幂等判重（返回 0 = 重复操作，直接丢弃）
            ├─ INCRBY article:like:count:{aid}
            └─ SADD article:like:users:{aid}
            │  仅 changed==1 才投递
            ▼
         RabbitMQ ex.like → q.like.write
            │
            ▼
        LikeConsumer：入内存聚合缓冲（O(1) 返回，立即 ACK）
            │  每 500 条 或 每 5 秒
            ▼
        batchUpsert(500 条) + batchIncrLikeCount(去重后 N 条)
```

**为什么必须 Lua**：`SISMEMBER` + `INCR` 是 check-then-act，拆开一定重复计数；
`MULTI/EXEC` 没有 if-else 能力。Lua 在 Redis 单线程原子执行是唯一正解。

**削峰三步**（`like/LikeAggregator.java`）：

- 关系聚合：`(userId, articleId) → 最新状态`，窗口内反复点赞/取消只留 1 条 upsert；
- 计数聚合：`articleId → Σdelta`，+1/-1 在窗口内**自然抵消**（"先赞后取消"净增为 0）；
- 批量刷盘：500 条一批、一次网络往返，配合 `rewriteBatchedStatements=true`，
  相比"一消息一写"DB 写入 TPS 下降 **80%+**。

**可靠性**（`config/RabbitConfig.java`）：

| 场景 | 处理 |
|---|---|
| 入缓冲成功 | `basicAck` |
| 消息处理异常 | `basicNack(requeue=false)` → 队列 DLX `ex.like.retry` → **10s TTL 延迟队列** → 自动回投主队列 |
| 重试 ≥ 3 次（读 `x-death.count`） | 转发 `ex.like.dlx` → `q.like.dlq` **死信队列** |
| 刷盘失败 | 数据**回滚回缓冲区**后 ACK，避免 redelivery 重复计数 |
| 生产者侧 | Publisher Confirm + Returns 回调 |

不用 `spring.rabbitmq.listener.simple.retry`：那是线程内 sleep 重投，会阻塞消费者、打满 prefetch、把消息钉在单节点。

**防漂移**：每小时 `syncLikeCountFromRedis` 以 Redis 计数（权威）全量回写 DB，把误差收敛到 0。

### 3.3 推拉结合 Feed 流

| 作者类型 | 写路径 | 读路径 |
|---|---|---|
| 普通用户（粉丝 < 5w） | 发件箱 + **异步扇出**到粉丝收件箱 | 只读收件箱 |
| 大 V / 明星医生（≥ 5w） | 只写**时间分桶**发件箱 | 读时**拉取**大 V 分片并归并 |

**BigKeySplitter** `feed/BigKeySplitter.java`

明星医生 350w 粉丝、数万条帖子塞进一个 ZSet 会造成：单 key 数十 MB、AOF/RDB 卡顿、Cluster 热点 slot。
方案是按时间分桶：

```
feed:outbox:shard:{authorId}:{bucketIndex}   每桶 6 小时
feed:outbox:meta:{authorId}                  桶索引（ZRANGEBYSCORE 裁剪时间范围）
```

- 分片 key **不带 hash tag**，CRC16 自然把不同桶散到不同 slot，消除热点；
- 读时从最新桶往回扫，凑够 limit 即停，历史桶可整体 TTL 或转冷存。

**FeedMergeService** `feed/FeedMergeService.java`

- k 路归并（最小堆），每路内部已按 score 降序，复杂度 O(N log k)；
- 每路最多取 `pageSize` 条，N 被限制在 `pageSize × (k+1)`，不随大 V 发帖量爆炸；
- 复合游标 `score:articleId` —— 只用时间戳会漏掉同毫秒发布的两条动态；
- 输出严格按 score 降序，去重后截断。

### 3.4 分级限流与打赏对账

**UserRateLimiter** `ratelimit/UserRateLimiter.java` + `lua/token_bucket.lua`

- 令牌桶（恒定速率补充 + 桶容量控突发），优于固定窗口（临界突刺 2 倍）与滑动窗口（存储成本）；
- 阈值差异化：`普通 20/s · 容量 40` / `认证医生 100/s · 容量 200`；
- 惰性填充（按时间差算令牌），一次 Lua 原子完成"补 + 扣"；
- Redis 抖动 **fail-open**（保护层不能反过来搞垮全站），切面 `@Order(1)` 让被限流请求不污染热点统计。

**DonateService** `donate/DonateService.java` —— 本地事务表最终一致性

```
BEGIN
  1. UPDATE t_user_wallet SET balance=balance-x WHERE user_id=? AND balance>=x   （条件扣款）
  2. INSERT t_donate_record       (INIT)       ← 业务数据
  3. INSERT t_donate_local_record (PENDING)    ← 待办消息，与业务同事务
COMMIT
afterCommit → 4. 调基金会（bizNo 幂等，指数退避 1/2/4/8…≤30min，最多 5 次）
              5. 成功：local=SUCCESS，业务单=CONFIRMED
                 终态失败：local=DEAD + 冲正退款 + 业务单=CLOSED
T+1 02:30  → 6. 与基金会日账单对账，兜底所有"沉默的不一致"
```

- 幂等：`t_donate_record.uk_biz_no` 唯一索引，重复提交只扣一次；
- 定时任务 `retryPendingRecords`（60s）扫描 `PENDING/SENT` 重投，实现"至少一次"；
- **T+1 日终对账** `DonateReconcileJob.reconcileDaily()`：双向比对
  （本地有远端无 `MISSING_REMOTE` / 远端有本地无 `MISSING_LOCAL` / 金额不一致 `AMOUNT_DIFF`），
  结果落 `t_reconcile_report`，差异项打 ERROR 日志并预留告警接入点。
- 对比 MQ 事务消息：本地事务表不依赖半消息能力，实现简单、可观测性更好，打赏是低频写，代价可接受。

---

## 四、目录结构

```
health-social/
├── pom.xml
├── 提示词记录.md
├── README.md
└── src/main/
    ├── java/com/health/social/
    │   ├── HealthSocialApplication.java
    │   ├── common/        Result / RedisKeys / UserLevel / UserContext / BizException
    │   ├── config/        Redis / Cache / Rabbit / ThreadPool / Web
    │   ├── entity/        Article / ArticleLike / UserFollow / UserProfile / Wallet / Donate*
    │   ├── mapper/        Mapper 接口 + bo/LikeCountDelta
    │   ├── cache/         HeavyKeeperDetector / HotArticleDetectAspect / CacheService
    │   │                  CacheWarmupService / CacheInvalidationListener / HotDetect
    │   ├── like/          LikeService / LikeProducer / LikeAggregator / LikeConsumer / LikeEvent
    │   ├── feed/          FeedService / BigKeySplitter / FeedMergeService / FollowService / FeedItem
    │   ├── ratelimit/     UserRateLimiter / RateLimit / RateLimitAspect
    │   ├── donate/        DonateService / DonateReconcileJob / FoundationClient / DTO
    │   ├── service/       ArticleService
    │   └── controller/    Article / Like / Feed / Follow / Donate / Cache
    └── resources/
        ├── application.yml
        ├── lua/           like.lua / heavy_keeper.lua / token_bucket.lua
        ├── mapper/        ArticleMapper.xml / ArticleLikeMapper.xml / UserFollowMapper.xml
        │                  UserWalletMapper.xml / DonateLocalRecordMapper.xml / DonateRecordMapper.xml
        └── db/schema.sql
```

---

## 五、关键配置（`application.yml`）

| 配置 | 默认 | 含义 |
|---|---|---|
| `health.hot.depth` / `width` / `top-n` | 4 / 100000 / 100 | HeavyKeeper 行数、桶数、榜单大小 |
| `health.hot.refresh-interval-ms` | 30000 | 热点集合刷新 + refreshAhead 周期 |
| `health.cache.l1-ttl-seconds` | 300 | Caffeine TTL（5 分钟） |
| `health.cache.l2-ttl-seconds` | 600 | Redis TTL（带随机抖动） |
| `health.like.batch-size` | 500 | 聚合刷盘条数阈值 |
| `health.like.flush-interval-ms` | 5000 | 聚合刷盘时间阈值 |
| `health.like.max-retry` / `retry-delay-ms` | 3 / 10000 | 最大重试次数、延迟重试 TTL |
| `health.feed.big-v-follower-threshold` | 50000 | 推/拉模式分界粉丝数 |
| `health.feed.bucket-ms` | 21600000 | 大 V 时间分桶跨度（6 小时） |
| `health.ratelimit.normal-rate` / `doctor-rate` | 20 / 100 | 分级令牌生成速率 |
| `health.donate.reconcile-cron` | `0 30 2 * * ?` | T+1 日终对账时间 |

---

## 六、生产落地补充清单

- **多实例定时任务**：`@Scheduled` 在多节点会重复执行，需用 Redisson 分布式锁或 XXL-Job 选主
  （涉及：点赞全量回写、打赏重试、T+1 对账、热点 refreshAhead）。
- **监控**：`GET /cache/stats` 的 `l1HitRate` 是核心 KPI，建议接入 Micrometer + Prometheus；
  低于阈值时自动调大 `l1-max-size` 或缩短 `refresh-interval-ms`。
- **优雅停机**：进程退出前调用 `POST /cache/like/flush`，把缓冲区剩余数据落库。
- **缓存穿透**：空值标记之上，可对文章 ID 叠加布隆过滤器。
- **Cluster**：HeavyKeeper 的两个 key 已用 `{article}` hash tag 保证同 slot；
  大 V 分片故意不加 tag 以打散热点。
