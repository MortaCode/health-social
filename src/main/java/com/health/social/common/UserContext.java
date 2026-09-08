package com.health.social.common;

/**
 * 用户上下文（演示用：真实环境由网关鉴权后透传 JWT 解析写入 ThreadLocal）。
 */
public final class UserContext {

    private static final ThreadLocal<Long> USER_ID = new ThreadLocal<>();
    private static final ThreadLocal<UserLevel> LEVEL = new ThreadLocal<>();

    private UserContext() {
    }

    public static void set(long userId, UserLevel level) {
        USER_ID.set(userId);
        LEVEL.set(level == null ? UserLevel.NORMAL : level);
    }

    public static Long getUserId() {
        Long id = USER_ID.get();
        return id == null ? 0L : id;
    }

    public static UserLevel getLevel() {
        UserLevel l = LEVEL.get();
        return l == null ? UserLevel.NORMAL : l;
    }

    public static void clear() {
        USER_ID.remove();
        LEVEL.remove();
    }
}
