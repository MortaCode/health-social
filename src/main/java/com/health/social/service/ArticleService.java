package com.health.social.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.health.social.cache.CacheService;
import com.health.social.common.RedisKeys;
import com.health.social.entity.Article;
import com.health.social.feed.FeedService;
import com.health.social.like.LikeService;
import com.health.social.mapper.ArticleMapper;
import com.health.social.recommend.event.ArticlePublishedEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;

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
    /**
     * 推荐模块接入点。
     *
     * <p>这里只依赖 Spring 的 {@link ApplicationEventPublisher}，<b>不依赖任何推荐模块的类</b>
     * （唯一引用是事件 DTO，且方向是 文章服务 → 事件，不反向依赖推荐服务）。
     * 因此：删掉整个 {@code recommend} 包，本类只需要去掉这一行 publishEvent 即可回退。
     */
    private final ApplicationEventPublisher eventPublisher;

    public ArticleService(CacheService cacheService,
                          ArticleMapper articleMapper,
                          LikeService likeService,
                          FeedService feedService,
                          ApplicationEventPublisher eventPublisher) {
        this.cacheService = cacheService;
        this.articleMapper = articleMapper;
        this.likeService = likeService;
        this.feedService = feedService;
        this.eventPublisher = eventPublisher;
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
        // 推流模块：写发件箱 + 扇出到粉丝收件箱（原有逻辑，未做任何改动）
        feedService.publish(article);
        // 推荐模块：发布事件（新增，唯一的接入点）。监听方失败不影响发帖，
        // 且推荐模块还有按 create_time 水位线的兜底扫描，最终一定会入候选池。
        eventPublisher.publishEvent(new ArticlePublishedEvent(
                article.getId(),
                article.getAuthorId(),
                article.getCreateTime().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()));
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
