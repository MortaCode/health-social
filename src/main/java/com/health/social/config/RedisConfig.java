package com.health.social.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Redis 基础配置：
 * <ul>
 *   <li>StringRedisTemplate：所有计数/集合/Lua 操作都基于字符串，避免 Java 序列化</li>
 *   <li>Object 型 RedisTemplate：存储 JSON 序列化后的文章详情（二级缓存）</li>
 *   <li>RedisMessageListenerContainer：缓存失效 / 预热广播</li>
 * </ul>
 */
@Configuration
public class RedisConfig {

    /**
     * 显式声明，避免依赖自动配置（Redis Cluster 场景下需要切换连接工厂实现）
     */
    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory factory) {
        StringRedisTemplate template = new StringRedisTemplate(factory);
        template.setKeySerializer(StringRedisSerializer.UTF_8);
        template.setValueSerializer(StringRedisSerializer.UTF_8);
        template.setHashKeySerializer(StringRedisSerializer.UTF_8);
        template.setHashValueSerializer(StringRedisSerializer.UTF_8);
        return template;
    }

    /**
     * 带类型信息的 JSON 序列化器（二级缓存存对象时使用）
     */
    @Bean
    public GenericJackson2JsonRedisSerializer genericJackson2JsonRedisSerializer() {
        return new GenericJackson2JsonRedisSerializer();
    }

    @Bean
    public RedisMessageListenerContainer redisMessageListenerContainer(RedisConnectionFactory factory) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(factory);
        return container;
    }

    /**
     * 缓存序列化上下文（供 RedisCacheManager 使用）
     */
    @Bean
    public RedisSerializationContext.SerializationPair<Object> objectSerializationPair(
            GenericJackson2JsonRedisSerializer serializer) {
        return RedisSerializationContext.SerializationPair.fromSerializer(serializer);
    }

    @Bean
    @Primary
    public ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        return mapper;
    }
}
