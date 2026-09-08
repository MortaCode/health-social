package com.health.social.common;

import lombok.Getter;

/**
 * 用户等级 —— 限流阈值的差异化依据
 */
@Getter
public enum UserLevel {

    /** 普通用户 */
    NORMAL(0, "普通用户"),
    /** 认证医生 */
    DOCTOR(1, "认证医生"),
    /** 明星医生（大 V） */
    STAR_DOCTOR(2, "明星医生");

    private final int code;
    private final String desc;

    UserLevel(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public static UserLevel of(Integer code) {
        if (code == null) {
            return NORMAL;
        }
        for (UserLevel l : values()) {
            if (l.code == code) {
                return l;
            }
        }
        return NORMAL;
    }

    public boolean isBigV() {
        return this == STAR_DOCTOR;
    }
}
