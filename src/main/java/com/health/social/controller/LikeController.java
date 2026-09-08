package com.health.social.controller;

import com.health.social.common.Result;
import com.health.social.common.UserContext;
import com.health.social.like.LikeService;
import com.health.social.ratelimit.RateLimit;
import lombok.Data;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 点赞接口。
 *
 * <p>写路径：Lua 原子更新 Redis → 状态变化才发 MQ → 消费者聚合落库。
 * 接口响应时间只有一次 Redis RTT，与 DB 完全解耦。
 */
@RestController
@RequestMapping("/like")
public class LikeController {

    private final LikeService likeService;

    public LikeController(LikeService likeService) {
        this.likeService = likeService;
    }

    @RateLimit(api = "like")
    @PostMapping("/{articleId}")
    public Result<LikeVO> toggle(@PathVariable Long articleId,
                                 @RequestParam(defaultValue = "1") int op) {
        long userId = UserContext.getUserId();
        LikeService.LikeResult result = likeService.toggle(userId, articleId, op == 1);
        return Result.ok(new LikeVO(result.isLiked(), result.getCount(), result.isChanged()));
    }

    @GetMapping("/count/{articleId}")
    public Result<Long> count(@PathVariable Long articleId) {
        return Result.ok(likeService.count(articleId));
    }

    @GetMapping("/status/{articleId}")
    public Result<Boolean> status(@PathVariable Long articleId) {
        return Result.ok(likeService.hasLiked(UserContext.getUserId(), articleId));
    }

    @Data
    public static class LikeVO {

        private final boolean liked;
        private final long count;
        private final boolean changed;

        public LikeVO(boolean liked, long count, boolean changed) {
            this.liked = liked;
            this.count = count;
            this.changed = changed;
        }
    }
}
