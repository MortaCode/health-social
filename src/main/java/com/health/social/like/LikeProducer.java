package com.health.social.like;

import com.health.social.config.RabbitConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 点赞事件生产者。
 *
 * <p>投递策略：普通发送 + Publisher Confirm（在 {@link RabbitConfig#rabbitTemplate} 中配置）。
 * 极端可靠场景可把事件先落"本地消息表"再异步发（参见打赏模块 {@code DonateService} 的同款做法），
 * 这里因为 Redis 计数已经是权威数据，丢一条消息只影响 DB 副本精度，可由全量回写任务修复。
 */
@Slf4j
@Component
public class LikeProducer {

    private final RabbitTemplate rabbitTemplate;

    public LikeProducer(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    public void send(LikeEvent event) {
        try {
            rabbitTemplate.convertAndSend(
                    RabbitConfig.EX_LIKE,
                    RabbitConfig.RK_LIKE_WRITE,
                    event,
                    message -> {
                        message.getMessageProperties()
                                .setHeader(RabbitConfig.HEADER_RETRY_COUNT, 0);
                        message.getMessageProperties().setDeliveryMode(
                                org.springframework.amqp.core.MessageDeliveryMode.PERSISTENT);
                        return message;
                    },
                    new CorrelationData(UUID.randomUUID().toString()));
        } catch (Exception e) {
            // 发送失败不能影响用户点赞结果（Redis 已成功），记录日志由全量回写兜底
            log.error("[Like] MQ 投递失败, event={}", event, e);
        }
    }
}
