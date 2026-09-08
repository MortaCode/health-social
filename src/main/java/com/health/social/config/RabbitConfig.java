package com.health.social.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

/**
 * RabbitMQ 拓扑与可靠性配置。
 *
 * <h3>点赞链路拓扑</h3>
 * <pre>
 *   生产者
 *     │  rk.like.write
 *     ▼
 *  [ex.like] ──────────────► [q.like.write]  ──(消费者异常, requeue=false)──┐
 *                                   ▲                                      │ x-dead-letter-exchange=ex.like.retry
 *                                   │                                      ▼
 *                                   │                                 [ex.like.retry]
 *                                   │                                      │ rk.like.retry
 *                                   │                                      ▼
 *                                   └──────(TTL 到期后 dead-letter)─── [q.like.retry.wait]
 *                                            x-message-ttl=10s
 *                                            x-dead-letter-exchange=ex.like
 *
 *   重试次数 &gt;= maxRetry  ──► [ex.like.dlx] ──► [q.like.dlq]（死信，人工/兜底处理）
 * </pre>
 *
 * <p><b>为什么不用 spring.rabbitmq.listener.simple.retry：</b>
 * 那是"线程内 sleep 后重投"，会阻塞消费者线程、打满 prefetch，
 * 并且失败一次就把消息钉在当前节点上。这里用 DLX + TTL 延迟队列做重试，
 * 重试发生在 broker 侧，不占用消费端资源，且重试间隔可控。
 */
@Slf4j
@Configuration
public class RabbitConfig {

    /* ================= 点赞 ================= */
    public static final String EX_LIKE = "ex.like";
    public static final String RK_LIKE_WRITE = "rk.like.write";
    public static final String Q_LIKE_WRITE = "q.like.write";

    public static final String EX_LIKE_RETRY = "ex.like.retry";
    public static final String RK_LIKE_RETRY = "rk.like.retry";
    public static final String Q_LIKE_RETRY_WAIT = "q.like.retry.wait";

    public static final String EX_LIKE_DLX = "ex.like.dlx";
    public static final String RK_LIKE_DLQ = "rk.like.dlq";
    public static final String Q_LIKE_DLQ = "q.like.dlq";

    /** 重试次数消息头 */
    public static final String HEADER_RETRY_COUNT = "x-retry-count";

    /* ================= Feed 扇出 ================= */
    public static final String EX_FEED = "ex.feed";
    public static final String RK_FEED_FANOUT = "rk.feed.fanout";
    public static final String Q_FEED_FANOUT = "q.feed.fanout";

    @Value("${health.like.retry-delay-ms:10000}")
    private long retryDelayMs;

    /* ------------------------------------------------------------------ */
    /*                              Exchanges                              */
    /* ------------------------------------------------------------------ */

    @Bean
    public TopicExchange likeExchange() {
        return ExchangeBuilder.topicExchange(EX_LIKE).durable(true).build();
    }

    @Bean
    public TopicExchange likeRetryExchange() {
        return ExchangeBuilder.topicExchange(EX_LIKE_RETRY).durable(true).build();
    }

    @Bean
    public DirectExchange likeDeadLetterExchange() {
        return ExchangeBuilder.directExchange(EX_LIKE_DLX).durable(true).build();
    }

    @Bean
    public TopicExchange feedExchange() {
        return ExchangeBuilder.topicExchange(EX_FEED).durable(true).build();
    }

    /* ------------------------------------------------------------------ */
    /*                               Queues                                */
    /* ------------------------------------------------------------------ */

    /**
     * 主业务队列：拒绝(requeue=false)的消息进入延迟重试交换机
     */
    @Bean
    public Queue likeWriteQueue() {
        return QueueBuilder.durable(Q_LIKE_WRITE)
                .deadLetterExchange(EX_LIKE_RETRY)
                .deadLetterRoutingKey(RK_LIKE_RETRY)
                // 队列级别保护：单队列最多堆积 100w 条，超出后从头部丢弃进入死信
                .maxLength(1_000_000)
                .maxLengthBytes(0)
                .build();
    }

    /**
     * 延迟重试队列：消息 TTL 到期后自动 dead-letter 回主交换机，形成重试闭环
     */
    @Bean
    public Queue likeRetryWaitQueue() {
        Map<String, Object> args = new HashMap<>(4);
        args.put("x-message-ttl", retryDelayMs);
        args.put("x-dead-letter-exchange", EX_LIKE);
        args.put("x-dead-letter-routing-key", RK_LIKE_WRITE);
        return QueueBuilder.durable(Q_LIKE_RETRY_WAIT).withArguments(args).build();
    }

    /**
     * 死信队列：超过最大重试次数的消息终点
     */
    @Bean
    public Queue likeDeadLetterQueue() {
        return QueueBuilder.durable(Q_LIKE_DLQ).build();
    }

    @Bean
    public Queue feedFanoutQueue() {
        return QueueBuilder.durable(Q_FEED_FANOUT)
                .deadLetterExchange(EX_LIKE_DLX)
                .deadLetterRoutingKey(RK_LIKE_DLQ)
                .build();
    }

    /* ------------------------------------------------------------------ */
    /*                              Bindings                               */
    /* ------------------------------------------------------------------ */

    @Bean
    public Binding likeWriteBinding() {
        return BindingBuilder.bind(likeWriteQueue()).to(likeExchange()).with(RK_LIKE_WRITE);
    }

    @Bean
    public Binding likeRetryWaitBinding() {
        return BindingBuilder.bind(likeRetryWaitQueue()).to(likeRetryExchange()).with(RK_LIKE_RETRY);
    }

    @Bean
    public Binding likeDeadLetterBinding() {
        return BindingBuilder.bind(likeDeadLetterQueue()).to(likeDeadLetterExchange()).with(RK_LIKE_DLQ);
    }

    @Bean
    public Binding feedFanoutBinding() {
        return BindingBuilder.bind(feedFanoutQueue()).to(feedExchange()).with(RK_FEED_FANOUT);
    }

    /* ------------------------------------------------------------------ */
    /*                          Template & Converter                       */
    /* ------------------------------------------------------------------ */

    /**
     * JSON 消息转换器。
     *
     * <p><b>信任包必须用前缀匹配</b>：Spring AMQP 4.x 原生的
     * {@code setTrustedPackages("com.health.social.*")} 用的是「精确包名 equals」，
     * 对 {@code com.health.social.like.LikeEvent} 这种类根本匹配不到
     * （其包名是 {@code com.health.social.like}），会抛
     * {@code IllegalArgumentException: ... is not in the trusted packages}。
     * 因此这里用 {@link PrefixTrustedClassMapper} 做前缀匹配，信任
     * {@code com.health.social} 整棵子树，对新增消息包天然兼容。
     *
     * <p>注意：Spring Boot 会把这个 MessageConverter bean 同时装配到
     * {@code RabbitTemplate} 和 {@code SimpleRabbitListenerContainerFactory}，
     * 也就是生产端与消费端共用一套序列化规则，两边必须一致。
     */
    @Bean
    public Jackson2JsonMessageConverter jackson2JsonMessageConverter() {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        Jackson2JsonMessageConverter converter = new Jackson2JsonMessageConverter(objectMapper);
        converter.setClassMapper(new PrefixTrustedClassMapper("com.health.social"));
        return converter;
    }

    /**
     * 生产者可靠性：
     * <ul>
     *   <li>ConfirmCallback：消息是否到达 Exchange</li>
     *   <li>ReturnsCallback：消息是否路由到 Queue（mandatory=true 才会回调）</li>
     * </ul>
     * 生产环境建议同时把发送失败的消息落入本地消息表兜底（与打赏模块同套路）。
     */
    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory,
                                         Jackson2JsonMessageConverter converter) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(converter);
        template.setMandatory(true);

        template.setConfirmCallback((correlationData, ack, cause) -> {
            if (!ack) {
                log.error("[MQ] 消息未到达 Exchange, correlation={}, cause={}", correlationData, cause);
                // TODO 落本地消息表 / 告警
            }
        });

        template.setReturnsCallback(returned ->
                log.error("[MQ] 消息无法路由到队列, exchange={}, routingKey={}, replyText={}",
                        returned.getExchange(), returned.getRoutingKey(), returned.getReplyText()));

        return template;
    }
}
