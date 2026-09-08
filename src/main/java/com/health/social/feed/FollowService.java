package com.health.social.feed;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.health.social.entity.UserProfile;
import com.health.social.mapper.UserFollowMapper;
import com.health.social.mapper.UserProfileMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 关注关系服务（Feed 推/拉模式判定的数据源）。
 *
 * <p>本地缓存"我关注的大 V 列表"：Feed 拉取是超高 QPS 的读接口，
 * 不能每次都回源查 DB。60s TTL 对关注关系变更是足够的容忍度。
 */
@Slf4j
@Service
public class FollowService {

    private final UserFollowMapper followMapper;
    private final UserProfileMapper profileMapper;

    @Value("${health.feed.big-v-follower-threshold:50000}")
    private long bigVThreshold;

    private final Cache<Long, List<Long>> bigVFolloweeCache = Caffeine.newBuilder()
            .maximumSize(100_000)
            .expireAfterWrite(60, TimeUnit.SECONDS)
            .build();

    private final Cache<Long, Boolean> bigVFlagCache = Caffeine.newBuilder()
            .maximumSize(200_000)
            .expireAfterWrite(300, TimeUnit.SECONDS)
            .build();

    public FollowService(UserFollowMapper followMapper, UserProfileMapper profileMapper) {
        this.followMapper = followMapper;
        this.profileMapper = profileMapper;
    }

    /**
     * 是否是"大 V"（粉丝数超阈值或明星医生）。
     * 大 V 发帖不扇出到收件箱，改由粉丝在读取时拉取（pull 模式）。
     */
    public boolean isBigV(long userId) {
        Boolean cached = bigVFlagCache.get(userId, id -> {
            UserProfile p = profileMapper.selectById(id);
            if (p == null) {
                return Boolean.FALSE;
            }
            boolean star = p.getLevel() != null && p.getLevel() >= 2;
            boolean manyFans = p.getFollowerCnt() != null && p.getFollowerCnt() >= bigVThreshold;
            return star || manyFans;
        });
        return Boolean.TRUE.equals(cached);
    }

    /**
     * 我关注的、且属于大 V 的账号列表（pull 模式的拉取源）
     */
    public List<Long> myBigVFollowees(long userId) {
        List<Long> cached = bigVFolloweeCache.get(userId, id -> {
            List<Long> followees = followMapper.selectFolloweeIds(id, 2000);
            if (followees == null || followees.isEmpty()) {
                return Collections.emptyList();
            }
            List<Long> bigVs = new ArrayList<>();
            for (Long f : followees) {
                if (isBigV(f)) {
                    bigVs.add(f);
                }
            }
            return bigVs;
        });
        return cached == null ? Collections.emptyList() : cached;
    }

    public List<Long> myFollowees(long userId) {
        List<Long> list = followMapper.selectFolloweeIds(userId, 5000);
        return list == null ? Collections.emptyList() : list;
    }

    /**
     * 关注关系变更：立刻失效本地缓存，保证下次 Feed 拉取读到最新关系
     */
    public void evict(long userId) {
        bigVFolloweeCache.invalidate(userId);
    }
}
