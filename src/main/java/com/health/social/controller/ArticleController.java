package com.health.social.controller;

import com.health.social.cache.HotDetect;
import com.health.social.common.Result;
import com.health.social.common.UserContext;
import com.health.social.entity.Article;
import com.health.social.like.LikeService;
import com.health.social.ratelimit.RateLimit;
import com.health.social.service.ArticleService;
import lombok.Data;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 文章接口。
 *
 * <p>详情接口同时挂载了两个切面：
 * <ul>
 *   <li>{@code @HotDetect}：访问频次进入 HeavyKeeper 统计（旁路，异步）；</li>
 *   <li>{@code @RateLimit}：按用户等级做令牌桶限流（Order 更靠前，被限流的请求不计入热点）。</li>
 * </ul>
 */
@RestController
@RequestMapping("/article")
public class ArticleController {

    private final ArticleService articleService;
    private final LikeService likeService;

    public ArticleController(ArticleService articleService, LikeService likeService) {
        this.articleService = articleService;
        this.likeService = likeService;
    }

    @HotDetect(value = "#articleId", type = "article")
    @RateLimit(api = "article.detail")
    @GetMapping("/{articleId}")
    public Result<ArticleVO> detail(@PathVariable Long articleId) {
        Article article = articleService.detail(articleId);
        if (article == null) {
            return Result.fail(404, "文章不存在");
        }
        long userId = UserContext.getUserId();
        ArticleVO vo = new ArticleVO();
        vo.setArticle(article);
        vo.setLiked(likeService.hasLiked(userId, articleId));
        return Result.ok(vo);
    }

    @RateLimit(api = "article.publish", permits = 5)
    @PostMapping
    public Result<Article> publish(@RequestBody Article article) {
        article.setAuthorId(UserContext.getUserId());
        return Result.ok(articleService.publish(article));
    }

    @Data
    public static class ArticleVO {

        private Article article;

        private Boolean liked;
    }
}
