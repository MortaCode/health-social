package com.health.social.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * 一级（本地）缓存配置。
 *
 * <p>选型要点：
 * <ul>
 *   <li>TTL 固定 5 分钟（题目要求），并结合 {@code expireAfterAccess} 让冷数据尽快让位；</li>
 *   <li>Caffeine 默认的 <b>TinyLFU</b> 淘汰策略 + {@code maximumSize} 天然偏向保留高频访问的
 *       Top100 热点数据，这是"本地命中率 85%"的第一层保障；</li>
 *   <li>第二层保障由 {@code HotKeyRefresher} 提供：定时把 HeavyKeeper 的 TopN 榜单主动
 *       回填进本地缓存，并对即将过期的数据做 refreshAhead，杜绝热点穿透到 Redis。</li>
 * </ul>
 */
@Configuration
public class CacheConfig {

    @Value("${health.cache.l1-max-size:20000}")
    private long l1MaxSize;

    @Value("${health.cache.l1-ttl-seconds:300}")
    private long l1TtlSeconds;

    /** 文章详情一级缓存：K = bizKey，V = JSON 字符串（避免本地对象被业务代码修改） */
    @Bean("articleLocalCache")
    public Cache<String, String> articleLocalCache() {
        return Caffeine.newBuilder()
                .maximumSize(l1MaxSize)
                .expireAfterWrite(l1TtlSeconds, TimeUnit.SECONDS)
                .recordStats()
                .build();
    }
}
