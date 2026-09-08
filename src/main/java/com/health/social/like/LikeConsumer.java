package com.health.social.like;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.health.social.cache.HeavyKeeperDetector;
import com.health.social.config.RabbitConfig;
import com.health.social.entity.ArticleLike;
import com.health.social.mapper.ArticleLikeMapper;
import com.health.social.mapper.ArticleMapper;
import com.health.social.mapper.bo.LikeCountDelta;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 点赞落库消费者。
 *
 * <h3>削峰三步</h3>
 * <ol>
 *   <li><b>入缓冲</b>：消息不做 DB 操作，只写入内存聚合缓冲，O(1) 返回；</li>
 *   <li><b>聚合</b>：按 (user, article) 取最新状态、按 article 累加 delta（详见 {@link LikeAggregator}）；</li>
 *   <li><b>批量刷盘</b>：满足 <b>500 条</b> 或 <b>5 秒</b> 任一条件即触发一次批量 upsert + 批量 update。</li>
 * </ol>
 *
 * <p>500 条一批、一次网络往返，配合 {@code rewriteBatchedStatements=true}，
 * 相比"一条消息一次 DB 写"TPS 下降约 80%。
 *
 * <h3>可靠性</h3>
 * <ul>
 *   <li>手动 ACK：只有入缓冲成功后才确认；</li>
 *   <li>刷盘失败：数据回滚回缓冲区后立即 ACK（数据在内存不丢，由下一次 flush 重试），
 *       避免 redelivery 造成重复计数；</li>
 *   <li>消息处理异常：nack(requeue=false) → 进入 10s 延迟重试队列 → 自动回投主队列；</li>
 *   <li>重试 &gt;= 3 次：转发到 {@code ex.like.dlx} → {@code q.like.dlq} 死信队列等待人工处理。</li>
 * </ul>
 */
@Slf4j
@Component
public class LikeConsumer {

    private final LikeAggregator aggregator;
    private final ArticleLikeMapper articleLikeMapper;
    private final ArticleMapper articleMapper;
    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;
    private final StringRedisTemplate redis;
    private final HeavyKeeperDetector detector;

    @Value("${health.like.batch-size:500}")
    private int batchSize;

    @Value("${health.like.max-retry:3}")
    private int maxRetry;

    private final AtomicLong flushedRows = new AtomicLong();
    private final AtomicLong flushedBatches = new AtomicLong();

    public LikeConsumer(LikeAggregator aggregator,
                        ArticleLikeMapper articleLikeMapper,
                        ArticleMapper articleMapper,
                        RabbitTemplate rabbitTemplate,
                        ObjectMapper objectMapper,
                        StringRedisTemplate redis,
                        HeavyKeeperDetector detector) {
        this.aggregator = aggregator;
        this.articleLikeMapper = articleLikeMapper;
        this.articleMapper = articleMapper;
        this.rabbitTemplate = rabbitTemplate;
        this.objectMapper = objectMapper;
        this.redis = redis;
        this.detector = detector;
    }

    /* ================================================================= */
    /*                            消息消费                                */
    /* ================================================================= */

    @RabbitListener(queues = RabbitConfig.Q_LIKE_WRITE,
            ackMode = "MANUAL",
            concurrency = "4-8")
    public void consume(Message message, Channel channel) throws IOException {
        long tag = message.getMessageProperties().getDeliveryTag();

        // 1) 解析 + 入缓冲
        try {
            LikeEvent event = objectMapper.readValue(message.getBody(), LikeEvent.class);
            aggregator.offer(event);
        } catch (Exception e) {
            log.error("[Like] 消息处理失败, retry={}", currentRetry(message), e);
            onFailure(message, channel, tag, e);
            return;
        }

        // 2) 达到批量阈值立即刷盘；刷盘失败时数据已回滚到缓冲区，直接 ACK
        try {
            if (aggregator.size() >= batchSize) {
                flush();
            }
        } catch (Exception e) {
            log.error("[Like] 批量刷盘失败，数据已回滚至缓冲区，等待下次 flush", e);
        }

        channel.basicAck(tag, false);
    }

    /**
     * 定时刷盘：每 5 秒（与 500 条阈值形成"双触发"）
     */
    @Scheduled(fixedDelayString = "${health.like.flush-interval-ms:5000}")
    public void timedFlush() {
        try {
            flush();
        } catch (Exception e) {
            log.error("[Like] 定时刷盘失败", e);
        }
    }

    /* ================================================================= */
    /*                              刷盘                                  */
    /* ================================================================= */

    /**
     * 把缓冲区一次性写入 DB。synchronized 保证多线程消费者不会并发写同一批。
     *
     * @return 本次写入的聚合条数，0 表示空缓冲
     */
    public synchronized int flush() {
        LikeAggregator.Snapshot snapshot = aggregator.drain();
        if (snapshot.isEmpty()) {
            return 0;
        }
        List<LikeEvent> events = snapshot.events();
        Map<Long, Long> deltaMap = snapshot.deltaMap();

        try {
            // 1) 批量 upsert 用户-文章点赞关系
            List<ArticleLike> rows = new ArrayList<>(events.size());
            for (LikeEvent e : events) {
                ArticleLike row = new ArticleLike();
                row.setId(IdWorker.getId());
                row.setUserId(e.getUserId());
                row.setArticleId(e.getArticleId());
                row.setStatus(e.getLiked() == null ? 0 : e.getLiked());
                rows.add(row);
            }
            if (!rows.isEmpty()) {
                articleLikeMapper.batchUpsert(rows);
            }

            // 2) 批量更新文章计数（delta 已在缓冲期相互抵消）
            List<LikeCountDelta> deltas = new ArrayList<>(deltaMap.size());
            deltaMap.forEach((articleId, d) -> {
                if (d != null && d != 0L) {
                    deltas.add(new LikeCountDelta(articleId, d));
                }
            });
            if (!deltas.isEmpty()) {
                articleMapper.batchIncrLikeCount(deltas);
            }

            flushedRows.addAndGet(events.size());
            flushedBatches.incrementAndGet();
            log.debug("[Like] 刷盘完成: 聚合 {} 条 -> {} 条 upsert, {} 条计数更新",
                    events.size(), rows.size(), deltas.size());
            return events.size();
        } catch (Exception e) {
            // 关键：失败要放回缓冲区，否则这批数据就丢了
            aggregator.rollback(events, deltaMap);
            throw new IllegalStateException("点赞批量落库失败", e);
        }
    }

    /* ================================================================= */
    /*                        全量回写（防漂移）                           */
    /* ================================================================= */

    /**
     * 每小时把热点文章的点赞数从 Redis 全量回写 DB。
     *
     * <p>增量聚合在极端情况（进程被 kill -9、DB 抖动丢批）下会产生漂移，
     * 而 Redis 计数器才是权威数据，因此用低频全量回写把误差收敛到 0。
     */
    @Scheduled(cron = "${health.like.sync-cron:0 15 * * * ?}")
    public void syncLikeCountFromRedis() {
        try {
            List<String> hotIds = detector.topN("article", 1000);
            if (hotIds.isEmpty()) {
                return;
            }
            List<LikeCountDelta> deltas = new ArrayList<>(hotIds.size());
            for (String id : hotIds) {
                Long articleId = Long.valueOf(id);
                deltas.add(new LikeCountDelta(articleId, LikeServiceHolder.countOf(redis, articleId)));
            }
            if (!deltas.isEmpty()) {
                articleMapper.batchSetLikeCount(deltas);
                log.info("[Like] 全量回写完成, 文章数 = {}", deltas.size());
            }
        } catch (Exception e) {
            log.warn("[Like] 全量回写失败（下个周期自动重试）", e);
        }
    }

    /* ================================================================= */
    /*                         失败处理 & 死信                            */
    /* ================================================================= */

    private void onFailure(Message message, Channel channel, long tag, Exception e) throws IOException {
        int retry = currentRetry(message);
        if (retry >= maxRetry) {
            // 超过重试上限：转发死信队列
            log.error("[Like] 重试 {} 次仍失败，转入死信队列, msgId={}", retry,
                    message.getMessageProperties().getMessageId(), e);
            rabbitTemplate.convertAndSend(RabbitConfig.EX_LIKE_DLX, RabbitConfig.RK_LIKE_DLQ, message);
            channel.basicAck(tag, false);
        } else {
            // requeue=false：由队列的 x-dead-letter-exchange 进入 10s 延迟队列，自动回投
            channel.basicNack(tag, false, false);
        }
    }

    /**
     * 读取重试次数：优先用 RabbitMQ 自动维护的 x-death.count
     */
    private int currentRetry(Message message) {
        Object xDeath = message.getMessageProperties().getHeader("x-death");
        if (xDeath instanceof List<?> list && !list.isEmpty()) {
            Object first = list.get(0);
            if (first instanceof Map<?, ?> map) {
                Object c = map.get("count");
                if (c instanceof Number n) {
                    return n.intValue();
                }
            }
        }
        Object rc = message.getMessageProperties().getHeader(RabbitConfig.HEADER_RETRY_COUNT);
        if (rc instanceof Number n) {
            return n.intValue();
        }
        return 0;
    }

    public long getFlushedRows() {
        return flushedRows.get();
    }

    public long getFlushedBatches() {
        return flushedBatches.get();
    }

    /** 读取 Redis 计数的静态工具（避免循环依赖） */
    static final class LikeServiceHolder {
        static long countOf(StringRedisTemplate redis, long articleId) {
            String v = redis.opsForValue()
                    .get(com.health.social.common.RedisKeys.articleLikeCount(articleId));
            if (v == null) {
                return 0L;
            }
            try {
                return Long.parseLong(v);
            } catch (NumberFormatException e) {
                return 0L;
            }
        }
    }
}
