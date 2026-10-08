package com.health.social.recommend.pipeline;

import com.health.social.entity.Article;
import com.health.social.mapper.RecArticleScanMapper;
import com.health.social.recommend.RecommendProperties;
import com.health.social.recommend.RecRedisKeys;
import com.health.social.recommend.event.ArticlePublishedEvent;
import com.health.social.recommend.profile.InterestProfileService;
import com.health.social.recommend.profile.ItemSimilarityService;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 候选池与索引维护器（推荐模块的"数据供给"）。
 *
 * <h3>两条互补的数据通路</h3>
 * <ol>
 *   <li><b>实时（事件驱动）</b>：{@link #onArticlePublished} 监听文章发布事件，
 *       立刻把文章写入候选池与各倒排索引。延迟毫秒级；</li>
 *   <li><b>兜底（扫描驱动）</b>：{@link #reconcile} 按 {@code (create_time, id)} 水位线
 *       定时增量扫描 {@code t_article}。事件丢失、服务重启期间的发布、历史存量数据，
 *       最终都会被这条通路补齐。</li>
 * </ol>
 * <b>这条兜底通路是"推荐模块可以完全独立部署"的关键</b>：
 * 即使把 {@code ArticleService} 里那一行事件发布删掉，推荐模块依然能通过扫描
 * 把候选池维护起来，只是新内容上线延迟从毫秒变成分钟级。
 *
 * <h3>为什么索引是幂等的</h3>
 * <p>两条通路都会重复处理同一篇文章（事件已经写了，扫描又会扫到）。
 * 因此所有写操作都是 {@code ZADD}（幂等）而不是 {@code INCR} ——
 * 重复处理不会造成数据偏差，这让"兜底扫描"可以放心地按水位线回退重扫。
 *
 * <h3>多实例安全</h3>
 * <p>{@code @Scheduled} 在集群里每个节点都会执行。本项目用 Redisson 分布式锁选主：
 * {@code tryLock(0, TimeUnit.SECONDS)} —— 拿不到锁立即跳过（不阻塞、不排队）。
 * <b>刻意不传 leaseTime</b>：这样 Redisson 会启用 watchdog 自动续期，
 * 任务跑多久锁就持有多久。如果传了固定租约（比如 120s），一旦任务超过 120s，
 * 锁会提前释放，另一个节点会并发执行同一批任务 —— 虽然本模块的写入是幂等的、
 * 不会算错数，但会白白浪费一次全量扫描。生产环境也可以用 XXL-Job 这类调度平台替代。
 */
@Slf4j
@Component
public class CandidatePoolMaintainer {

    /** 候选池/索引 TTL：30 天（超期内容不再参与推荐，由离线侧承接） */
    private static final long INDEX_TTL_SECONDS = 30L * 24 * 3600;

    /** 单次兜底扫描的最大轮数，防止一次任务跑太久 */
    private static final int MAX_RECONCILE_ROUNDS = 20;

    private final StringRedisTemplate redis;
    private final RecommendProperties props;
    private final InterestProfileService interestProfileService;
    private final ItemSimilarityService similarityService;
    private final RecArticleScanMapper scanMapper;
    private final RedissonClient redisson;

    public CandidatePoolMaintainer(StringRedisTemplate redis,
                                   RecommendProperties props,
                                   InterestProfileService interestProfileService,
                                   ItemSimilarityService similarityService,
                                   RecArticleScanMapper scanMapper,
                                   RedissonClient redisson) {
        this.redis = redis;
        this.props = props;
        this.interestProfileService = interestProfileService;
        this.similarityService = similarityService;
        this.scanMapper = scanMapper;
        this.redisson = redisson;
    }

    /* ================================================================= */
    /*                     通路一：文章发布事件（实时）                     */
    /* ================================================================= */

    /**
     * 监听文章发布事件，实时建立索引。
     *
     * <p>刻意<b>不加 {@code @Async}</b>：事件发布在 {@code ArticleService.publish} 的事务内，
     * 这里做的全是 Redis 写（微秒级），同步执行既简单又能保证"发布成功 = 索引就绪"。
     * 如果索引失败，异常在这里被吞掉并降级到兜底扫描，不会让文章发布失败。
     */
    @EventListener
    public void onArticlePublished(ArticlePublishedEvent event) {
        try {
            index(event.articleId(), event.authorId(), event.publishTimeMs());
            log.debug("[Pool] 文章已入推荐候选池: article={}, author={}", event.articleId(), event.authorId());
        } catch (Exception e) {
            // 绝不因为推荐索引失败而影响发帖：兜底扫描会补上
            log.warn("[Pool] 文章入池失败，等待兜底扫描补齐: article={}", event.articleId(), e);
        }
    }

    /**
     * 把一篇文章写入候选池与各倒排索引（标签从 DB 读取）。
     *
     * <p>写 4 类索引：
     * <ol>
     *   <li>{@code rec:cand:fresh} —— 全站新内容池（新鲜度召回 + 相似度重建的输入）；</li>
     *   <li>{@code rec:author:articles:{authorId}} —— 作者维度（用于排查与后续扩展）；</li>
     *   <li>{@code rec:tag:articles:{tagId}} —— 标签倒排（兴趣召回的输入）；</li>
     *   <li>候选池按数量裁剪，避免无限增长。</li>
     * </ol>
     *
     * <p>所有写操作都是 {@code ZADD}（幂等），因此事件通路与兜底扫描可以重复处理同一篇文章。
     */
    public void index(long articleId, long authorId, long publishTimeMs) {
        index(articleId, authorId, publishTimeMs, interestProfileService.articleTags(articleId));
    }

    /**
     * 入池重载：标签由调用方批量查好后传入。
     *
     * <p>兜底扫描必须用这个重载：一轮 500 篇如果逐篇查标签，就是 500 次 SQL；
     * 批量查只需 1 次。
     */
    public void index(long articleId, long authorId, long publishTimeMs, List<Long> tagIds) {
        long score = publishTimeMs > 0 ? publishTimeMs : System.currentTimeMillis();
        String member = String.valueOf(articleId);

        String freshKey = RecRedisKeys.CAND_FRESH;
        redis.opsForZSet().add(freshKey, member, score);
        redis.opsForZSet().add(RecRedisKeys.authorArticles(authorId), member, score);

        if (tagIds != null) {
            for (Long tagId : tagIds) {
                if (tagId != null) {
                    redis.opsForZSet().add(RecRedisKeys.tagArticles(tagId), member, score);
                }
            }
        }

        // 裁剪候选池：只保留最新的 poolMaxSize 条
        int max = Math.max(1, props.getPoolMaxSize());
        Long size = redis.opsForZSet().size(freshKey);
        if (size != null && size > max) {
            redis.opsForZSet().removeRange(freshKey, 0, size - max - 1);
        }
        redis.expire(freshKey, Duration.ofSeconds(INDEX_TTL_SECONDS));
    }

    /* ================================================================= */
    /*                     通路二：水位线增量扫描（兜底）                   */
    /* ================================================================= */

    /**
     * 按 {@code (create_time, id)} 水位线增量扫描文章表，补齐候选池。
     *
     * <p>水位线格式：{@code createTimeMillis:articleId}，只由本方法读写
     * （事件通路刻意不碰水位线，避免两条通路互相干扰导致漏扫）。
     */
    @Scheduled(cron = "${health.recommend.pool-reconcile-cron:0 */2 * * * ?}")
    public void reconcile() {
        RLock lock = redisson.getLock(RecRedisKeys.LOCK_POOL);
        boolean locked = false;
        try {
            locked = lock.tryLock(0, TimeUnit.SECONDS);
            if (!locked) {
                return;
            }
            long[] cursor = loadWatermark();
            LocalDateTime wmTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(cursor[0]), ZoneId.systemDefault());
            long wmId = cursor[1];

            int batch = Math.max(1, props.getPoolReconcileBatch());
            int total = 0;
            for (int round = 0; round < MAX_RECONCILE_ROUNDS; round++) {
                List<Article> list = scanMapper.scanAfter(wmTime, wmId, batch);
                if (list == null || list.isEmpty()) {
                    break;
                }
                // 先批量查标签（1 次 SQL / 批），再逐篇入池 —— 避免 500 篇 = 500 次 SQL
                List<Long> ids = new ArrayList<>(list.size());
                for (Article a : list) {
                    if (a != null && a.getId() != null && a.getCreateTime() != null
                            && (a.getStatus() == null || a.getStatus() == 1)) {
                        ids.add(a.getId());
                    }
                }
                Map<Long, List<Long>> tags = interestProfileService.articleTagsBatch(ids);

                for (Article a : list) {
                    if (a == null || a.getId() == null || a.getCreateTime() == null) {
                        continue;
                    }
                    if (a.getStatus() != null && a.getStatus() != 1) {
                        continue;
                    }
                    long publishMs = a.getCreateTime().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
                    index(a.getId(), a.getAuthorId() == null ? 0L : a.getAuthorId(), publishMs,
                            tags.getOrDefault(a.getId(), Collections.emptyList()));
                }
                Article last = list.get(list.size() - 1);
                if (last.getCreateTime() == null || last.getId() == null) {
                    break;
                }
                wmTime = last.getCreateTime();
                wmId = last.getId();
                saveWatermark(last.getCreateTime(), last.getId());
                total += list.size();
                if (list.size() < batch) {
                    break;
                }
            }
            if (total > 0) {
                log.info("[Pool] 兜底扫描完成，本批入池 {} 篇，水位线={}:{}", total, wmTime, wmId);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.warn("[Pool] 兜底扫描失败", e);
        } finally {
            if (locked && lock.isHeldByCurrentThread()) {
                try {
                    lock.unlock();
                } catch (Exception ignore) {
                    // ignore
                }
            }
        }
    }

    private long[] loadWatermark() {
        try {
            String raw = redis.opsForValue().get(RecRedisKeys.POOL_WATERMARK);
            if (raw == null || raw.isBlank()) {
                // 首次运行：从最早开始扫，把存量文章全部补进候选池
                return new long[]{0L, 0L};
            }
            int idx = raw.indexOf(':');
            long millis = Long.parseLong(idx > 0 ? raw.substring(0, idx) : raw);
            long id = idx > 0 ? Long.parseLong(raw.substring(idx + 1)) : 0L;
            return new long[]{millis, id};
        } catch (Exception e) {
            log.warn("[Pool] 水位线解析失败，从 0 重新开始", e);
            return new long[]{0L, 0L};
        }
    }

    private void saveWatermark(LocalDateTime createTime, long id) {
        long millis = createTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        redis.opsForValue().set(RecRedisKeys.POOL_WATERMARK, millis + ":" + id);
    }

    /* ================================================================= */
    /*                          相似度重建（近线）                         */
    /* ================================================================= */

    @Scheduled(cron = "${health.recommend.sim-rebuild-cron:0 */10 * * * * ?}")
    public void rebuildSimilarity() {
        RLock lock = redisson.getLock(RecRedisKeys.LOCK_SIM);
        boolean locked = false;
        try {
            locked = lock.tryLock(0, TimeUnit.SECONDS);
            if (!locked) {
                return;
            }
            similarityService.rebuildContentSimilarity();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.warn("[Pool] 相似度重建失败", e);
        } finally {
            if (locked && lock.isHeldByCurrentThread()) {
                try {
                    lock.unlock();
                } catch (Exception ignore) {
                    // ignore
                }
            }
        }
    }

    /* ================================================================= */
    /*                       兴趣画像回写（定时）                          */
    /* ================================================================= */

    /**
     * 把在线兴趣画像回写 DB。
     *
     * <p>输入是 {@code rec:active:users}（推荐请求时写入的活跃用户榜），
     * 只回写最近活跃的用户 —— 不活跃用户的兴趣分数其实没变化，没必要扫全量。
     */
    @Scheduled(cron = "${health.recommend.interest-persist-cron:0 */5 * * * ?}")
    public void persistInterests() {
        RLock lock = redisson.getLock(RecRedisKeys.LOCK_POOL + ":interest");
        boolean locked = false;
        try {
            locked = lock.tryLock(0, TimeUnit.SECONDS);
            if (!locked) {
                return;
            }
            int window = Math.max(1, props.getActiveUserWindowSize());
            Set<String> users = redis.opsForZSet().reverseRange(RecRedisKeys.ACTIVE_USERS, 0, window - 1L);
            if (users == null || users.isEmpty()) {
                return;
            }
            int ok = 0;
            for (String u : users) {
                try {
                    long userId = Long.parseLong(u);
                    if (interestProfileService.persist(userId) > 0) {
                        ok++;
                    }
                } catch (NumberFormatException ignore) {
                    // 脏数据跳过
                }
            }
            log.info("[Pool] 兴趣画像回写完成：活跃用户 {} 个，实际写入 {} 个", users.size(), ok);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.warn("[Pool] 兴趣画像回写失败", e);
        } finally {
            if (locked && lock.isHeldByCurrentThread()) {
                try {
                    lock.unlock();
                } catch (Exception ignore) {
                    // ignore
                }
            }
        }
    }

    /** 运维：手动触发一次全量入池（把存量文章灌进候选池） */
    public int backfill(int limit) {
        List<Article> list = scanMapper.scanAfter(
                LocalDateTime.ofInstant(Instant.ofEpochMilli(0L), ZoneId.systemDefault()), 0L,
                Math.max(1, limit));
        if (list == null || list.isEmpty()) {
            return 0;
        }
        int n = 0;
        for (Article a : list) {
            if (a == null || a.getId() == null || a.getCreateTime() == null) {
                continue;
            }
            long publishMs = a.getCreateTime().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
            index(a.getId(), a.getAuthorId() == null ? 0L : a.getAuthorId(), publishMs);
            n++;
        }
        return n;
    }

    /** 运维：候选池当前规模 */
    public long poolSize() {
        Long size = redis.opsForZSet().size(RecRedisKeys.CAND_FRESH);
        return size == null ? 0L : size;
    }

    /** 运维：当前水位线（可读字符串） */
    public String watermark() {
        String raw = redis.opsForValue().get(RecRedisKeys.POOL_WATERMARK);
        return raw == null ? "" : raw;
    }
}
