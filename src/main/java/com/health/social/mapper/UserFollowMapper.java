package com.health.social.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.health.social.entity.UserFollow;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface UserFollowMapper extends BaseMapper<UserFollow> {

    /** 查询某用户关注的所有人 */
    List<Long> selectFolloweeIds(@Param("followerId") Long followerId, @Param("limit") int limit);

    /** 查询某用户的粉丝（推模式扇出用） */
    List<Long> selectFollowerIds(@Param("followeeId") Long followeeId, @Param("offset") long offset, @Param("limit") int limit);

    /** 粉丝总数 */
    long countFollowers(@Param("followeeId") Long followeeId);
}
