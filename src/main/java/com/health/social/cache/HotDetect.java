package com.health.social.cache;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 热点探测埋点。
 *
 * <p>标注在需要统计访问频次的方法上（通常是文章详情、短视频详情等读接口）：
 * <pre>{@code
 *  @HotDetect(value = "#articleId", type = "article")
 *  public Result<ArticleVO> detail(@PathVariable Long articleId) { ... }
 * }</pre>
 *
 * <p>被 {@link HotArticleDetectAspect} 拦截，交给 HeavyKeeper 统计。
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface HotDetect {

    /**
     * SpEL 表达式，用于从方法参数中提取被统计对象 ID，如 {@code #articleId}、{@code #req.articleId}
     */
    String value();

    /**
     * 业务类型，不同业务使用独立的 HeavyKeeper 表与榜单
     */
    String type() default "article";
}
