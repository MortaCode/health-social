package com.health.social.recommend.feedback;

import com.health.social.entity.RecExposure;
import com.health.social.entity.RecFeedback;
import com.health.social.mapper.RecExposureMapper;
import com.health.social.mapper.RecFeedbackMapper;
import com.health.social.recommend.RecommendProperties;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 曝光 / 反馈流水的异步批量落库缓冲。
 *
 * <h3>为什么不能同步写 DB</h3>
 * <p>一次推荐请求会产生 20 条曝光记录。如果同步 {@code INSERT}，等于把推荐接口的
 * RT 直接绑在 DB 写入上 —— 而曝光数据的价值是<b>离线的</b>（训练 CTR 模型、做效果归因），
 * 晚 5 秒落库完全不影响任何线上逻辑。
 *
 * <h3>设计取舍：内存队列 + 定期刷盘（不是 MQ）</h3>
 * <p>与点赞模块用 RabbitMQ 削峰不同，曝光数据的可靠性要求低得多：
 * 丢几条曝光只影响离线样本的完整性，不会造成资损或状态不一致。
 * 因此用最轻量的 {@code ConcurrentLinkedQueue} + {@code @Scheduled} 刷盘：
 * <ul>
 *   <li>零额外中间件依赖；</li>
 *   <li>写入是 O(1) 的入队，绝不阻塞推荐主链路；</li>
 *   <li>队列有上限，堆积时<b>直接丢弃</b>而不是把内存打爆 —— 这是明确的取舍，
 *       不是疏漏：宁可少几条离线样本，也不能因为离线数据把线上服务搞挂。</li>
 * </ul>
 */
@Slf4j
@Component
public class RecommendPersistenceBuffer {

    /** 队列上限：超过后丢弃新数据（保护线上内存） */
    private static final int MAX_BUFFER = 50_000;

    private final Queue<RecExposure> exposures = new ConcurrentLinkedQueue<>();
    private final Queue<RecFeedback> feedbacks = new ConcurrentLinkedQueue<>();

    private final RecExposureMapper exposureMapper;
    private final RecFeedbackMapper feedbackMapper;
    private final RecommendProperties props;

    public RecommendPersistenceBuffer(RecExposureMapper exposureMapper,
                                      RecFeedbackMapper feedbackMapper,
                                      RecommendProperties props) {
        this.exposureMapper = exposureMapper;
        this.feedbackMapper = feedbackMapper;
        this.props = props;
    }

    /** 入队一条曝光（O(1)，不阻塞主链路） */
    public void offerExposure(RecExposure exposure) {
        if (exposures.size() >= MAX_BUFFER) {
            log.warn("[RecBuffer] 曝光队列已满({})，丢弃本条曝光", MAX_BUFFER);
            return;
        }
        exposures.offer(exposure);
    }

    /** 入队一条反馈 */
    public void offerFeedback(RecFeedback feedback) {
        if (feedbacks.size() >= MAX_BUFFER) {
            log.warn("[RecBuffer] 反馈队列已满({})，丢弃本条反馈", MAX_BUFFER);
            return;
        }
        feedbacks.offer(feedback);
    }

    /** 定时刷盘：每 {@code flush-interval-ms} 一次，单次最多 {@code flush-batch-size} 条 */
    @Scheduled(fixedDelayString = "${health.recommend.flush-interval-ms:5000}")
    public void flush() {
        int batch = Math.max(1, props.getFlushBatchSize());
        flushExposures(batch);
        flushFeedbacks(batch);
    }

    /** 停机前尽力把缓冲刷完，避免白白丢掉内存里的样本 */
    @PreDestroy
    public void flushOnShutdown() {
        log.info("[RecBuffer] 停机刷盘开始, exposure={}, feedback={}", exposures.size(), feedbacks.size());
        int guard = 0;
        while ((!exposures.isEmpty() || !feedbacks.isEmpty()) && guard++ < 100) {
            flushExposures(Math.max(1, props.getFlushBatchSize()));
            flushFeedbacks(Math.max(1, props.getFlushBatchSize()));
        }
    }

    private void flushExposures(int batch) {
        List<RecExposure> list = drain(exposures, batch);
        if (list.isEmpty()) {
            return;
        }
        try {
            exposureMapper.insertBatch(list);
        } catch (Exception e) {
            // 曝光是离线样本，落库失败直接丢弃：不重试、不回队，避免故障时无限重试放大压力
            log.error("[RecBuffer] 曝光批量落库失败，丢弃 {} 条", list.size(), e);
        }
    }

    private void flushFeedbacks(int batch) {
        List<RecFeedback> list = drain(feedbacks, batch);
        if (list.isEmpty()) {
            return;
        }
        try {
            feedbackMapper.insertBatch(list);
        } catch (Exception e) {
            log.error("[RecBuffer] 反馈批量落库失败，丢弃 {} 条", list.size(), e);
        }
    }

    private static <T> List<T> drain(Queue<T> queue, int max) {
        List<T> out = new ArrayList<>(Math.min(max, 256));
        for (int i = 0; i < max; i++) {
            T v = queue.poll();
            if (v == null) {
                break;
            }
            out.add(v);
        }
        return out;
    }

    /** 运维视角：当前积压量 */
    public int pending() {
        return exposures.size() + feedbacks.size();
    }
}
