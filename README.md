# 高并发健康社交平台 · 后端核心模块

> Spring Boot **4.1.1 GA**（Jakarta EE）+ **JDK 17** + Maven
> 覆盖五大核心模块：热点探测与多级缓存、亿级点赞削峰、推拉结合 Feed 流、分级限流与打赏最终一致性、**个性化推荐（多路召回 + 排序 + 打散）**。
>
> 其中 **推荐模块是后加的独立模块**：只新增 `recommend` 包与 `db/rec_*.sql`，对既有代码的改动只有
> `ArticleService.publish()` 里的 **1 行事件发布**。详见 [3.5](#35-个性化推荐多路召回--排序--打散)。

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
# 1. 初始化数据库（既有四大模块）
mysql -uroot -p < src/main/resources/db/schema.sql

# 1.1 推荐模块的独立表（只新增，不改动上面的表）
mysql -uroot -p < src/main/resources/db/rec_schema.sql

# 1.2 可选：推荐模块演示数据（8 个标签 + 15 篇健康科普文章 + 兴趣画像）
#     没有内容的话推荐链路会返回空页，建议本地联调时执行
mysql -uroot -p < src/main/resources/db/rec_demo_data.sql

# 2. 修改配置
#    src/main/resources/application.yml 中的 MySQL / Redis / RabbitMQ 地址

# 3. 编译 & 启动
mvn -DskipTests package
java -jar target/health-social.jar
```

> **首次启动推荐模块**：候选池是靠"文章发布事件 + 定时兜底扫描"填充的。
> 如果是给已有数据的库上线，先调一次 `POST /recommend/pool/backfill?limit=500`
> 把存量文章灌进候选池，或等 2 分钟让兜底扫描自动补齐。

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
| GET | `/recommend?cursor=&size=20&scene=home` | **个性化推荐流**（多路召回 + 排序 + 打散） |
| POST | `/recommend/feedback` | **推荐反馈**（`CLICK` / `LIKE` / `DISLIKE`），驱动兴趣画像与相似召回 |
| GET | `/recommend/debug/{articleId}` | 单篇文章的推荐特征解释（标签 / 相似文章 / 实时计数 / 权重） |
| GET | `/recommend/stats` | 推荐模块运行状态（召回通道、候选池、水位线、缓冲积压） |
| POST | `/recommend/sim/rebuild` | 手动触发相似度重建 |
| POST | `/recommend/pool/backfill?limit=500` | 手动把存量文章灌入候选池 |
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

### 3.5 个性化推荐（多路召回 + 排序 + 打散）

> 本模块是**后加的独立模块**。设计约束是"新开一个模块，不要过度修改推流模块"，
> 因此它在包、表、Redis key、线程、接口五个层面都与推流模块隔离，
> 对既有代码的改动只有 1 行。

#### 与推流模块的边界

| 维度 | 推流模块（Feed 流） | 推荐模块（Recommend） |
|---|---|---|
| 包 | `com.health.social.feed` | `com.health.social.recommend` |
| 接口 | `GET /feed` | `GET /recommend` |
| 数据驱动 | **社交图谱**（我关注了谁） | **兴趣图谱 + 热度 + 内容相似** |
| 排序依据 | 发布时间倒序 | 7 特征加权（含个性化） |
| Redis 前缀 | `feed:*` | `rec:*` |
| 表 | `t_article` / `t_user_follow` / `t_profile` | 只**新增** `t_tag` / `t_article_tag` / `t_user_interest` / `t_rec_exposure` / `t_rec_feedback` |
| 对既有代码的改动 | —— | **仅 `ArticleService.publish()` 新增 1 行 `publishEvent`** |

推荐模块对推流模块的复用方式是**只读**：
`FollowRecallChannel` 注入 `FollowService` 判断大 V、注入 `BigKeySplitter.range()` 读大 V 时间分桶，
读的是**同一份** `feed:outbox:*` 存储（保证"Feed 里能看到的内容，推荐里也能看到"），
但**不写入、不修改**推流模块的任何逻辑。

#### 链路

```
GET /recommend
      │  ① 用户侧特征一次性加载（关注列表 / 兴趣画像 / 最近点赞）—— 多路共享，避免重复 IO
      ▼
 ┌───────────────────────── ② 多路召回（5 路，各自容错） ─────────────────────────┐
 │  hot      热点召回    HeavyKeeper TopN（只读复用推流模块已统计好的 hk:top:{hot}） │
 │  follow   关注召回    只读 feed:outbox / 大V分片，作者轮转避免被单个大V刷屏        │
 │  interest 兴趣召回    rec:interest:{uid} → rec:tag:articles:{tagId} 倒排索引     │
 │  sim      相似召回    rec:like:recent:{uid} → rec:sim:{aid}（Item-CF + 标签Jaccard）│
 │  fresh    新鲜度召回  rec:cand:fresh（新内容扶持，破除马太效应）                  │
 └────────────────────────────────┬─────────────────────────────────────────────┘
                                  │  Map 去重 + 通道置信度（多路命中加权）
                                  ▼
                        ③ 富化（批量，禁止 N+1）
                           文章本体 / 标签 / 作者等级 / 实时曝光点击计数（MGET）
                                  ▼
                        ④ 过滤
                           已曝光（在线去重）/ 自己发的 / 已点赞 / 已删除
                                  ▼
                        ⑤ 排序（Ranker，7 特征线性加权 + 负反馈乘性惩罚）
                                  ▼
                        ⑥ 打散（Diversifier，作者/标签约束，三阶段贪心保证填满）
                                  ▼
                        ⑦ 回填 VO + 曝光上报（Redis 去重 + 异步批量落库）
```

#### 多路召回

| 通道 | 数据源 | 作用 | 冷启动表现 |
|---|---|---|---|
| `hot` | `hk:top:{hot}`（推流模块已在统计） | 全站热度，覆盖"内容质量"信号 | ✅ 始终可用 |
| `follow` | `feed:outbox:*` / `feed:outbox:shard:*`（只读） | 社交关系，解决"我在意的人发了什么" | ✅ 有基础关注即可 |
| `interest` | `rec:interest:{uid}` + `rec:tag:articles:{tagId}` | 个性化主力，解决"我关心什么" | ❌ 无画像时空掉 |
| `sim` | `rec:like:recent:{uid}` + `rec:sim:{aid}` | 行为相似，解决"和我口味相近的人在看什么" | ❌ 无行为时空掉 |
| `fresh` | `rec:cand:fresh` | 新内容扶持 + 全链路兜底 | ✅ 始终可用 |

设计要点：

- **通道必须自愈**。每个通道内部 `try/catch` 吞异常并降级为空，编排层再兜一层。
  一路召回挂掉只损失覆盖率，绝不能让整页推荐失败 —— 召回是"多多益善"，不是"全有或全无"。
- **多路共识加分**。同一篇文章被多路命中时，`Candidate.channelScore()` 用
  `0.6*max + 0.4*min(1,Σ)` 计算置信度：既不让"被一个高置信通道强命中"吃亏，
  又奖励"多路同时看中"的内容。
- **必须有 fresh 通道**。只靠热点 + 兴趣 + 相似会形成马太效应：新内容没有历史数据，
  永远进不了热榜也匹配不上画像，于是永远没有曝光机会，内容生态会快速板结。
- **接口化可插拔**。新增一路召回 = 新增一个 `RecallChannel` 实现类，编排/排序/打散零改动。

#### 排序（`Ranker`）

显式加权线性模型，不是深度模型 —— 因为推荐结果要能回答"为什么推给我"，线性权重天然可归因
（`GET /recommend/debug/{articleId}` 会把每个特征的归一化值和最终分摊开）。

| 特征 | 计算 | 为什么这样算 |
|---|---|---|
| `hot` | `h=ln(1+imp+5·clk)`，再 `h/(1+h)` | 对数压制头部（10 万曝光不该是 1 万的 10 倍收益）；点击权重 ×5，因为点击比曝光珍贵 |
| `quality` | 点赞率的 **Wilson 下界**（95%） | 直接算 `likes/imp` 会让"1 曝光 1 赞"的偶然样本（100%）压过"1 万曝光 3000 赞"的优质内容；Wilson 下界对样本量做置信惩罚，样本越少越保守 |
| `fresh` | `0.5^(age/halfLife)` | **半衰期**语义（24h 衰减到一半）。注意别写成 `exp(-age/halfLife)`，那样 24h 后是 `1/e≈0.368`，衰减比预期快得多 |
| `interest` | 文章标签与兴趣画像的饱和累加 `1-e^(-Σ)` | 命中标签越多分越高但收敛到 1，避免"堆标签"的内容无限得分 |
| `follow` | 0 / 1 | 权重刻意只有 0.06：推荐流不是关注流，关注权重过大就退化成推流模块的时间线，失去探索价值 |
| `author` | 明星医生 1.0 / 认证 0.6 / 普通 0.25 | 医疗内容对可信度极敏感，权威作者应获得稳定加成 |
| `channel` | 召回通道置信度 | 多路共识 |

**负反馈是乘性的**：`1/(1+dislikes)` —— 1 次不感兴趣砍半，2 次降到 1/3。
乘性惩罚的语义是"这条内容对我无效"，比线性减分更符合直觉，且保证无论其它特征多高都排不到前面。

**冷启动单独一档权重**：新用户既没有兴趣画像也没有点赞行为，如果沿用老用户权重，
`interest` 项恒为 0 等于白占 14% 的权重预算。冷启动档把 `interest` 归零，
权重让给 `hot`(0.38) 与 `fresh`(0.28)，并整体提升"不依赖个人行为"的特征占比。

#### 打散（`Diversifier`）

纯按分数取 TopN 会出现"一个明星医生的 5 篇高血压科普霸屏前 5 条"。
三阶段贪心解决，且**保证一定填满**：

```
Phase A  硬约束：同作者 ≤ maxPerAuthor、同标签 ≤ maxPerTag、且不与上一条同作者
Phase B  放宽相邻：允许相邻同作者，数量约束仍生效
Phase C  兜底补齐：按分数顺序把剩余候选填满
```

阶段化而不是一次性加约束，是因为"约束太紧导致选不满"比"多样性差"更糟：
用户要滑到底才能触发下一页。宁可放宽多样性，也不能给半页 —— 这是工业界的通行取舍。

#### 曝光去重与分页语义（重要）

推荐流是"每次请求重新算一遍"，算法又是确定性的：不做处理的话，用户下拉刷新 10 次会看到同一批内容 10 次。
本模块用**曝光集合当分页状态**：

- 每次返回的条目立即写入 `rec:exposed:{userId}`（ZSet，score=曝光时间）；
- 下次请求时这些条目被过滤掉，于是自然返回"没看过的新内容"；
- `cursor` 只用于客户端记录已消费数量，服务端**不做 offset 跳过**（推荐流的 Top20 每次都不一样，offset 毫无意义）。

用 ZSet 而不是 Set 是为了**滑动窗口**：只记住最近 `exposure-window-size`(2000) 条，
被挤出窗口的内容自然重新进入候选 —— 好内容过一段时间重看仍有价值，不该"看一次就永久消失"。

#### 反馈闭环

| 动作 | 系统动作 |
|---|---|
| `CLICK` | 点击计数 +1；文章标签兴趣分 +0.3 |
| `LIKE` | 点击计数 +1；兴趣分 +1.0；**写入共现矩阵**（`rec:sim` 实时 ZINCRBY） |
| `DISLIKE` | 不感兴趣计数 +1；兴趣分 −2.0；写入"近期不再推荐"集合（硬过滤） |

四类数据同时落两处：Redis（在线立刻生效）+ `t_rec_exposure` / `t_rec_feedback`（离线训练与效果归因）。
反馈入口复用既有的 `@RateLimit` 令牌桶，但用独立的 `api` 标识，配额与 Feed 接口互不挤占。

#### 数据供给：实时事件 + 兜底扫描

```
文章发布 ──┬─ ArticleService.publish() 发 Spring 事件（1 行）
           │        └→ @EventListener 立刻写候选池 + 标签倒排
           │
           └─ @Scheduled 每 2 分钟按 (create_time, id) 水位线增量扫描 t_article（兜底）
                    └→ 事件丢失 / 服务重启期间的发布 / 历史存量数据，最终都会被补齐
```

- **兜底扫描是"推荐模块可独立部署"的关键**：即使删掉那一行事件发布，候选池仍会被扫描维护起来，
  只是新内容上线延迟从毫秒级变成分钟级。
- 所有索引写操作都是 `ZADD`（幂等），所以两条通路可以放心重复处理同一篇文章。
- 水位线用 `(create_time, id)` **双键游标**：`create_time` 是秒精度且文章常批量发布，
  只用时间做游标会漏（用 `>` 漏掉同秒剩余）或死循环（用 `>=` 反复扫同一批）。
- 多实例用 Redisson 锁选主，且**刻意不传 leaseTime** 以启用 watchdog 自动续期
  （传固定租约的话，任务超时后锁提前释放，另一个节点会重复执行整批扫描）。

#### 独立表结构（`db/rec_schema.sql`）

| 表 | 用途 |
|---|---|
| `t_tag` | 标签字典 |
| `t_article_tag` | 文章标签（内容侧特征，**旁挂**，不往 `t_article` 加字段） |
| `t_user_interest` | 兴趣画像持久化副本（在线读写走 Redis，本表由定时任务回写，用于恢复与离线分析） |
| `t_rec_exposure` | 曝光流水（离线训练样本） |
| `t_rec_feedback` | 反馈流水（正负样本） |

演示数据见 `db/rec_demo_data.sql`（8 个标签 + 15 篇健康科普文章 + 标签关联 + 2 个用户兴趣画像）。

#### 并发与一致性细节

- **兴趣分加减必须用 Lua**（`lua/rec_interest.lua`）：`读 → 加减 → 裁剪到 [0,cap] → 决定 HSET/HDEL → 续期`
  是 check-then-act，拆成多条命令在"用户并发点击 + 定时衰减"同时发生时会丢失更新、
  或把分数减成负数。单 key 操作，无需 hash tag。
- **批量点赞判断用 `executePipelined`**：800 条候选逐个 `SISMEMBER` 就是 800 次 RTT，会把 P99 拉爆。
- **实时计数用 `MGET`**：曝光/点击/不感兴趣 3 次 MGET 拿回全部候选的计数。
- **曝光/反馈异步批量落库**：内存队列 + 5 秒刷盘，队列满了**直接丢弃**而不是打爆内存 ——
  这是明确取舍：曝光是离线样本，丢几条不影响线上，但绝不能因为离线数据把线上服务搞挂。
- **分层容错**：召回通道 = 尽力而为（挂了只少一路）；文章富化 = 硬依赖（拿不到就没内容可推，快速失败）；
  标签/作者等级 = 打分依赖（失败降级为 0，不影响内容展示）。

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
    │   │                  Tag / ArticleTag / UserInterest / RecExposure / RecFeedback
    │   ├── mapper/        Mapper 接口 + bo/LikeCountDelta + RecArticleScanMapper
    │   ├── cache/         HeavyKeeperDetector / HotArticleDetectAspect / CacheService
    │   │                  CacheWarmupService / CacheInvalidationListener / HotDetect
    │   ├── like/          LikeService / LikeProducer / LikeAggregator / LikeConsumer / LikeEvent
    │   ├── feed/          FeedService / BigKeySplitter / FeedMergeService / FollowService / FeedItem
    │   ├── ratelimit/     UserRateLimiter / RateLimit / RateLimitAspect
    │   ├── donate/        DonateService / DonateReconcileJob / FoundationClient / DTO
    │   ├── service/       ArticleService
    │   ├── controller/    Article / Like / Feed / Follow / Donate / Cache
    │   └── recommend/     ★ 推荐模块（完全新增，独立可插拔）
    │       ├── RecommendController / RecommendService / RecommendProperties / RecRedisKeys
    │       ├── event/     ArticlePublishedEvent（唯一的接入契约）
    │       ├── model/     Candidate / RecallContext / RecommendPage
    │       ├── recall/    RecallChannel(接口) / Hot / Follow / Interest / CoLike / Fresh
    │       ├── rank/      Ranker / RankFeatures
    │       ├── diversify/ Diversifier
    │       ├── filter/    ExposureFilter
    │       ├── profile/   InterestProfileService / ItemSimilarityService
    │       ├── feedback/  RecommendFeedbackService / RecommendPersistenceBuffer
    │       └── pipeline/  RecommendPipeline / CandidatePoolMaintainer
    └── resources/
        ├── application.yml
        ├── lua/           like.lua / heavy_keeper.lua / token_bucket.lua / rec_interest.lua
        ├── mapper/        ArticleMapper.xml / ArticleLikeMapper.xml / UserFollowMapper.xml
        │                  UserWalletMapper.xml / DonateLocalRecordMapper.xml / DonateRecordMapper.xml
        │                  RecExposureMapper.xml / RecFeedbackMapper.xml / UserInterestMapper.xml
        │                  RecArticleScanMapper.xml
        └── db/            schema.sql（既有四大模块）
                           rec_schema.sql（推荐模块独立表）
                           rec_demo_data.sql（推荐模块演示数据，可选）

test/
└── java/com/health/social/recommend/
    ├── rank/RankerTest.java           排序特征单测（Wilson / 半衰期 / 冷启动权重 / 负反馈）
    └── diversify/DiversifierTest.java 打散单测（作者与标签约束 / 一定填满 / 不改输入）
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
| `health.recommend.recall-limit-per-channel` | 200 | 单路召回上限（5 路 → 最多 1000 条候选） |
| `health.recommend.candidate-limit` | 800 | 进入排序阶段的候选上限 |
| `health.recommend.freshness-half-life-hours` | 24 | 时间衰减半衰期（小时） |
| `health.recommend.weights.*` | 见 yml | 老用户排序权重档（hot/quality/fresh/interest/follow/author/channel） |
| `health.recommend.cold-start-weights.*` | 见 yml | 冷启动权重档（interest 归零，权重让给 hot/fresh） |
| `health.recommend.max-per-author` / `max-per-tag` | 2 / 3 | 一页内同作者 / 同标签的条数上限（打散） |
| `health.recommend.exposure-window-size` | 2000 | 在线曝光集合保留条数（滑动窗口，决定"多久后可以重推"） |
| `health.recommend.filter-liked` | true | 是否硬过滤已点赞内容 |
| `health.recommend.interest-cap` | 10.0 | 兴趣分上限 |
| `health.recommend.sim-rebuild-cron` | `0 */10 * * * ?` | 相似度（标签 Jaccard）重建周期 |
| `health.recommend.pool-reconcile-cron` | `0 */2 * * * ?` | 候选池兜底扫描周期（事件丢失的保险） |
| `health.recommend.interest-persist-cron` | `0 */5 * * * ?` | 兴趣画像回写 DB 周期 |
| `health.recommend.flush-interval-ms` | 5000 | 曝光/反馈缓冲刷盘间隔 |

---

## 六、生产落地补充清单

- **多实例定时任务**：`@Scheduled` 在多节点会重复执行，需用 Redisson 分布式锁或 XXL-Job 选主
  （涉及：点赞全量回写、打赏重试、T+1 对账、热点 refreshAhead；
  推荐模块的候选池扫描 / 相似度重建 / 兴趣回写已内置 Redisson 锁选主）。
- **监控**：`GET /cache/stats` 的 `l1HitRate` 是核心 KPI，建议接入 Micrometer + Prometheus；
  低于阈值时自动调大 `l1-max-size` 或缩短 `refresh-interval-ms`。
- **优雅停机**：进程退出前调用 `POST /cache/like/flush`，把缓冲区剩余数据落库。
- **缓存穿透**：空值标记之上，可对文章 ID 叠加布隆过滤器。
- **Cluster**：HeavyKeeper 的两个 key 已用 `{article}` hash tag 保证同 slot；
  大 V 分片故意不加 tag 以打散热点。

### 推荐模块专项

- **相似度替换点**：`ItemSimilarityService.rebuildContentSimilarity()` 目前用"标签 Jaccard"近似行为相似度，
  属于单机可行的近线方案。生产应换成"离线集群（Spark/Flink）产出共现矩阵 → 灌回 `rec:sim:*`"，
  召回通道 / 排序 / 打散都不需要改。
- **排序升级路径**：`Ranker` 是显式加权线性模型。等 `t_rec_exposure` / `t_rec_feedback`
  积累到足够样本后，可替换为 LR/GBDT —— 特征工程已在 `RankFeatures` 里做完了，只改一个类。
- **曝光上报压力**：当前由服务端在返回结果时上报，N 条曝光 = N 次 `INCR`。
  曝光量比点击量大两个数量级，真实平台通常改为**客户端埋点上报 + 服务端离线聚合**，
  并改用 `executePipelined` 把 N 次往返压成 1 次。
- **兴趣画像冷启动**：新用户兴趣为空时会走冷启动权重档（偏热点/新鲜）。
  可进一步接入"注册时选择兴趣标签"，把冷启动窗口缩短。
- **候选池容量**：`pool-max-size` 默认 5000，是按"单机 Redis + 演示规模"设的。
  上量后应改为"按时间窗口 + 分片"（参考推流模块 `BigKeySplitter` 的做法），避免单个 ZSet 变成大 key。
- **监控**：`GET /recommend/stats` 暴露了候选池规模、水位线、曝光数、缓冲积压；
  建议再补两个核心指标：**各召回通道的命中量与最终贡献占比**（`PipelineResult.channelHits` 已产出，
  接 Micrometer 即可）、**曝光→点击→点赞的漏斗转化率**（由 `t_rec_exposure` / `t_rec_feedback` 离线算）。
