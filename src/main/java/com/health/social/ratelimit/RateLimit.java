package com.health.social.ratelimit;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 接口限流埋点。
 *
 * <pre>{@code
 *   @RateLimit(api = "feed", permits = 1)
 *   public Result<FeedPage> feed(...) { ... }
 * }</pre>
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RateLimit {

    /** 接口标识，用于区分计数桶 */
    String api();

    /** 单次消耗令牌数 */
    int permits() default 1;
}
