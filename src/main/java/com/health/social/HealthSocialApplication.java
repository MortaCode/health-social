package com.health.social;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 高并发健康社交平台 · 启动类
 *
 * <p>模块清单：
 * <ol>
 *   <li>热点探测与多级缓存（HeavyKeeper + Caffeine L1 / Redis L2）</li>
 *   <li>原子点赞与异步削峰（Lua + MQ 批量聚合落库 + 死信队列）</li>
 *   <li>推拉结合 Feed 流（Inbox 推模式 + 大 V 拉模式 + ZSet 时间分桶）</li>
 *   <li>分级限流（Redis 令牌桶）与公益打赏最终一致性（本地事务表 + T+1 对账）</li>
 * </ol>
 *
 * <p>注意：本项目<b>没有</b>使用 Spring Cache 抽象（{@code @Cacheable}），
 * 而是直接操作 Caffeine + Redis 手写多级缓存 —— 因为需要"热点保活、单飞回源、
 * 跨节点失效广播"等行为，Spring Cache 的 CacheManager 抽象表达不了。
 * 所以这里不能加 {@code @EnableCaching}，否则会因缺少 CacheManager 而启动失败。
 */
@EnableAsync
@EnableScheduling
@MapperScan("com.health.social.mapper")
@SpringBootApplication
public class HealthSocialApplication {

    public static void main(String[] args) {
        SpringApplication.run(HealthSocialApplication.class, args);
    }

    /** MyBatis-Plus 分页插件 */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        return interceptor;
    }
}
