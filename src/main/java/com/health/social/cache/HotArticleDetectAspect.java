package com.health.social.cache;

import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.expression.MethodBasedEvaluationContext;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/**
 * 热点探测切面。
 *
 * <p>职责：在业务读接口执行<b>之后</b>把访问事件喂给 HeavyKeeper，
 * 由 Redis 侧原子完成频次统计与 TopK 榜单更新。
 *
 * <p>设计约束：
 * <ol>
 *   <li><b>旁路化</b>：任何异常都被吞掉并降级，热点统计不准可以接受，接口挂了不行；</li>
 *   <li><b>不拖慢主链路</b>：默认异步（提交到线程池）上报，只有线程池打满时才由主线程执行；</li>
 *   <li><b>可采样</b>：通过 {@code health.hot.sample-rate} 在超大流量下按比例上报。</li>
 * </ol>
 */
@Slf4j
@Aspect
@Component
public class HotArticleDetectAspect {

    private final HeavyKeeperDetector detector;

    @SuppressWarnings("unused")
    private final Executor executor;

    @Value("${health.hot.sample-rate:1}")
    private int sampleRate;

    @Value("${health.hot.async:true}")
    private boolean async;

    private final ExpressionParser parser = new SpelExpressionParser();
    private final ParameterNameDiscoverer nameDiscoverer = new DefaultParameterNameDiscoverer();

    /** SpEL 表达式缓存，避免每次解析 */
    private final Map<String, Expression> expressionCache = new ConcurrentHashMap<>(64);

    public HotArticleDetectAspect(HeavyKeeperDetector detector,
                                  @org.springframework.beans.factory.annotation.Qualifier("commonExecutor") Executor executor) {
        this.detector = detector;
        this.executor = executor;
    }

    @Around("@annotation(hotDetect)")
    public Object around(ProceedingJoinPoint joinPoint, HotDetect hotDetect) throws Throwable {
        // 先执行业务逻辑，保证探测逻辑不影响主流程耗时与结果
        Object result = joinPoint.proceed();

        try {
            Object item = resolveItem(joinPoint, hotDetect.value());
            if (item == null) {
                return result;
            }
            String itemStr = String.valueOf(item);
            String type = hotDetect.type();

            if (async) {
                executor.execute(() -> detector.addWithSample(type, itemStr, sampleRate));
            } else {
                detector.addWithSample(type, itemStr, sampleRate);
            }
        } catch (Exception e) {
            log.debug("[HotDetect] 热点统计跳过: {}", e.getMessage());
        }
        return result;
    }

    /**
     * 解析 SpEL，从方法参数中提取被统计对象 ID
     */
    private Object resolveItem(ProceedingJoinPoint joinPoint, String expression) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        Object[] args = joinPoint.getArgs();

        Expression expr = expressionCache.computeIfAbsent(expression, parser::parseExpression);
        EvaluationContext context = new MethodBasedEvaluationContext(joinPoint.getTarget(), method, args, nameDiscoverer);
        return expr.getValue(context);
    }
}
