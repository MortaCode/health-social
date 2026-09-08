package com.health.social.cache;

import com.health.social.common.RedisKeys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;

/**
 * 缓存一致性：订阅失效广播，清除本节点的一级缓存。
 *
 * <p>Cache-Aside 模式下，更新 DB 后删除 Redis 缓存，但其他节点的进程内缓存是无感知的。
 * 这里通过 Redis Pub/Sub 广播，把失效动作扩散到所有实例。
 * 消息丢失的兜底是 L1 的 5 分钟 TTL —— 最坏情况下不一致窗口 ≤ 5 分钟。
 */
@Slf4j
@Component
public class CacheInvalidationListener implements MessageListener {

    private final CacheService cacheService;
    private final RedisMessageListenerContainer container;

    public CacheInvalidationListener(CacheService cacheService, RedisMessageListenerContainer container) {
        this.cacheService = cacheService;
        this.container = container;
    }

    @PostConstruct
    public void register() {
        container.addMessageListener(this, new ChannelTopic(RedisKeys.TOPIC_CACHE_INVALIDATE));
        container.addMessageListener(this, new ChannelTopic(RedisKeys.TOPIC_CACHE_WARMUP));
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        String channel = new String(message.getChannel(), StandardCharsets.UTF_8);
        String body = new String(message.getBody(), StandardCharsets.UTF_8);
        if (body == null || body.isEmpty()) {
            return;
        }
        if (RedisKeys.TOPIC_CACHE_INVALIDATE.equals(channel)) {
            cacheService.evictLocal(body);
            log.debug("[Cache] 收到失效广播, key={}", body);
        } else if (RedisKeys.TOPIC_CACHE_WARMUP.equals(channel)) {
            // 格式：WARMUP:<topN>
            log.info("[Cache] 收到预热广播: {}", body);
        }
    }

    /** 本地缓存容量（供监控） */
    @Value("${health.cache.l1-max-size:20000}")
    private long l1MaxSize;

    public long getL1MaxSize() {
        return l1MaxSize;
    }
}
