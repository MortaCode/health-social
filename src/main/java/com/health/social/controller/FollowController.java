package com.health.social.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.health.social.common.Result;
import com.health.social.common.UserContext;
import com.health.social.entity.UserFollow;
import com.health.social.feed.FeedService;
import com.health.social.mapper.UserFollowMapper;
import com.health.social.ratelimit.RateLimit;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

/**
 * 关注关系接口（Feed 推拉模式的开关）。
 */
@RestController
@RequestMapping("/follow")
public class FollowController {

    private final UserFollowMapper followMapper;
    private final FeedService feedService;

    public FollowController(UserFollowMapper followMapper, FeedService feedService) {
        this.followMapper = followMapper;
        this.feedService = feedService;
    }

    @RateLimit(api = "follow")
    @PostMapping("/{followeeId}")
    public Result<String> follow(@PathVariable Long followeeId) {
        long followerId = UserContext.getUserId();
        UserFollow exist = followMapper.selectOne(new LambdaQueryWrapper<UserFollow>()
                .eq(UserFollow::getFollowerId, followerId)
                .eq(UserFollow::getFolloweeId, followeeId));
        if (exist == null) {
            UserFollow f = new UserFollow();
            f.setId(IdWorker.getId());
            f.setFollowerId(followerId);
            f.setFolloweeId(followeeId);
            f.setStatus(1);
            f.setCreateTime(LocalDateTime.now());
            followMapper.insert(f);
        } else if (exist.getStatus() == null || exist.getStatus() == 0) {
            exist.setStatus(1);
            exist.setUpdateTime(LocalDateTime.now());
            followMapper.updateById(exist);
        }
        feedService.onFollow(followerId, followeeId);
        return Result.ok("已关注");
    }

    @RateLimit(api = "follow")
    @DeleteMapping("/{followeeId}")
    public Result<String> unfollow(@PathVariable Long followeeId) {
        long followerId = UserContext.getUserId();
        UserFollow exist = followMapper.selectOne(new LambdaQueryWrapper<UserFollow>()
                .eq(UserFollow::getFollowerId, followerId)
                .eq(UserFollow::getFolloweeId, followeeId));
        if (exist != null) {
            exist.setStatus(0);
            exist.setUpdateTime(LocalDateTime.now());
            followMapper.updateById(exist);
        }
        feedService.onUnfollow(followerId, followeeId);
        return Result.ok("已取关");
    }
}
