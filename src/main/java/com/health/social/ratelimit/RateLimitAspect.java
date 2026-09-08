package com.health.social.ratelimit;

import com.health.social.common.BizException;
import com.health.social.common.UserContext;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 限流切面。
 *
 * <p>{@code @Order(1)} 保证限流先于热点探测等业务切面执行 —— 被限流的请求
 * 不应该污染热点统计，否则刷接口的 IP 会把垃圾内容刷成"热点"。
 */
@Slf4j
@Aspect
@Order(1)
@Component
public class RateLimitAspect {

    private final UserRateLimiter rateLimiter;

    public RateLimitAspect(UserRateLimiter rateLimiter) {
        this.rateLimiter = rateLimiter;
    }

    @Before("@annotation(rateLimit)")
    public void before(org.aspectj.lang.JoinPoint joinPoint, RateLimit rateLimit) {
        long userId = UserContext.getUserId();
        UserRateLimiter.RateResult result =
                rateLimiter.tryAcquire(userId, rateLimit.api(), UserContext.getLevel(), rateLimit.permits());
        if (!result.isAllowed()) {
            log.warn("[RateLimit] 触发限流, userId={}, api={}, retryAfterMs={}",
                    userId, rateLimit.api(), result.getRetryAfterMs());
            throw BizException.tooManyRequests("请求过于频繁，请稍后再试");
        }
    }
}
