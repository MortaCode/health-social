package com.health.social.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.health.social.entity.UserWallet;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;

public interface UserWalletMapper extends BaseMapper<UserWallet> {

    /**
     * 原子扣款：条件更新 + 乐观锁，返回影响行数（0 表示余额不足）
     */
    int deduct(@Param("userId") Long userId, @Param("amount") BigDecimal amount);

    /**
     * 冲正退款
     */
    int refund(@Param("userId") Long userId, @Param("amount") BigDecimal amount);
}
