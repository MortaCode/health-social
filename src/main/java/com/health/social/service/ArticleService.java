package com.health.social.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.health.social.cache.CacheService;
import com.health.social.common.RedisKeys;
import com.health.social.entity.Article;
import com.health.social.feed.FeedService;
import com.health.social.like.LikeService;
import com.health.social.mapper.ArticleMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 文章服务（缓存读写 + 发布投递 Feed）。
 */
@Slf4j
@Service
public class ArticleService {

    private final CacheService cacheService;
    private final ArticleMapper articleMapper;
    private final LikeService likeService;
    private final FeedService feedService;

    public ArticleService(CacheService cacheService,
                          ArticleMapper articleMapper,
                          LikeService likeService,
                          FeedService feedService) {
        this.cacheService = cacheService;
        this.articleMapper = articleMapper;
        this.likeService = likeService;
        this.feedService = feedService;
    }

    /**
     * 文章详情：走多级缓存，点赞数以 Redis 计数为准（实时）
     */
    public Article detail(long articleId) {
        String bizKey = RedisKeys.articleCache(articleId);
        Article article = cacheService.get(bizKey, Article.class, () -> articleMapper.selectById(articleId));
        if (article != null) {
            // 缓存里的 likeCount 是落库快照，这里用 Redis 权威计数覆盖，保证实时性
            article.setLikeCount(likeService.count(articleId));
        }
        return article;
    }

    /**
     * 发布文章：写库 → 写缓存 → 投递 Feed
     */
    @Transactional(rollbackFor = Exception.class)
    public Article publish(Article article) {
        if (article.getId() == null) {
            article.setId(IdWorker.getId());
        }
        if (article.getCreateTime() == null) {
            article.setCreateTime(LocalDateTime.now());
        }
        if (article.getLikeCount() == null) {
            article.setLikeCount(0L);
        }
        articleMapper.insert(article);
        cacheService.put(RedisKeys.articleCache(article.getId()), article);
        feedService.publish(article);
        return article;
    }

    /**
     * 更新：Cache-Aside，先更库再删缓存（含跨节点失效广播）
     */
    @Transactional(rollbackFor = Exception.class)
    public void update(Article article) {
        articleMapper.updateById(article);
        cacheService.invalidate(RedisKeys.articleCache(article.getId()));
    }
}
