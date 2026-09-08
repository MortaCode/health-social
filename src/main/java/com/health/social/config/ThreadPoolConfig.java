package com.health.social.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 业务线程池：Feed 扇出 / 缓存预热等重 IO 操作，避免占用 Tomcat 与 MQ 线程。
 */
@Configuration
public class ThreadPoolConfig {

    /** Feed 推模式扇出：写粉丝收件箱 */
    @Bean("feedFanoutExecutor")
    public Executor feedFanoutExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(8);
        executor.setMaxPoolSize(32);
        executor.setQueueCapacity(200_000);
        executor.setKeepAliveSeconds(60);
        executor.setThreadNamePrefix("feed-fanout-");
        // 收件箱丢失可接受（下次发帖仍会写入），直接丢弃并在监控里体现
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.DiscardOldestPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }

    /** 通用异步任务：热点回填、缓存预热 */
    @Bean("commonExecutor")
    public Executor commonExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(50_000);
        executor.setThreadNamePrefix("common-async-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}
