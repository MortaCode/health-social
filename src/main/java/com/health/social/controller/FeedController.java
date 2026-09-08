package com.health.social.controller;

import com.health.social.common.Result;
import com.health.social.common.UserContext;
import com.health.social.entity.Article;
import com.health.social.feed.FeedItem;
import com.health.social.feed.FeedMergeService;
import com.health.social.like.LikeService;
import com.health.social.ratelimit.RateLimit;
import com.health.social.service.ArticleService;
import lombok.Data;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Feed 流接口（推拉结合聚合后的时间线）。
 */
@RestController
@RequestMapping("/feed")
public class FeedController {

    private final FeedMergeService feedMergeService;
    private final ArticleService articleService;
    private final LikeService likeService;

    public FeedController(FeedMergeService feedMergeService,
                          ArticleService articleService,
                          LikeService likeService) {
        this.feedMergeService = feedMergeService;
        this.articleService = articleService;
        this.likeService = likeService;
    }

    /**
     * 拉取首页时间线
     *
     * @param cursor 上一页返回的游标，首页不传
     * @param size   页大小，默认 20
     */
    @RateLimit(api = "feed")
    @GetMapping
    public Result<FeedVO> feed(@RequestParam(required = false) String cursor,
                               @RequestParam(required = false) Integer size) {
        long userId = UserContext.getUserId();
        FeedMergeService.FeedPage page = feedMergeService.merge(userId, cursor, size);

        // 批量补齐文章详情（真实场景走 mget 或本地缓存，这里复用多级缓存）
        List<FeedArticle> articles = new ArrayList<>(page.items().size());
        List<Long> ids = new ArrayList<>(page.items().size());
        for (FeedItem item : page.items()) {
            ids.add(item.getArticleId());
        }
        Map<Long, Boolean> likedMap = likeService.batchHasLiked(userId, ids);
        for (FeedItem item : page.items()) {
            Article a = articleService.detail(item.getArticleId());
            if (a == null) {
                continue;
            }
            FeedArticle fa = new FeedArticle();
            fa.setArticle(a);
            fa.setSource(item.getSource());
            fa.setScore((long) item.getScore());
            fa.setLiked(Boolean.TRUE.equals(likedMap.get(item.getArticleId())));
            articles.add(fa);
        }
        return Result.ok(new FeedVO(articles, page.nextCursor(), page.hasMore()));
    }

    @Data
    public static class FeedVO {

        private final List<FeedArticle> list;
        private final String nextCursor;
        private final boolean hasMore;

        public FeedVO(List<FeedArticle> list, String nextCursor, boolean hasMore) {
            this.list = list;
            this.nextCursor = nextCursor;
            this.hasMore = hasMore;
        }
    }

    @Data
    public static class FeedArticle {

        private Article article;
        private Long score;
        private Integer source;
        private Boolean liked;
    }
}
