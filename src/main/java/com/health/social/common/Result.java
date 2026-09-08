package com.health.social.common;

import java.io.Serializable;

/**
 * 统一响应体
 *
 * @param code 0 成功，非 0 业务错误
 * @param msg  描述
 * @param data 数据
 */
public record Result<T>(int code, String msg, T data) implements Serializable {

    public static <T> Result<T> ok(T data) {
        return new Result<>(0, "ok", data);
    }

    public static <T> Result<T> ok() {
        return new Result<>(0, "ok", null);
    }

    public static <T> Result<T> fail(int code, String msg) {
        return new Result<>(code, msg, null);
    }

    public static <T> Result<T> fail(String msg) {
        return new Result<>(-1, msg, null);
    }

    public boolean success() {
        return code == 0;
    }
}
