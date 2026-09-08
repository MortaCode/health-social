package com.health.social.common;

/**
 * 业务异常
 */
public class BizException extends RuntimeException {

    private final int code;

    public BizException(String message) {
        this(-1, message);
    }

    public BizException(int code, String message) {
        super(message);
        this.code = code;
    }

    public int getCode() {
        return code;
    }

    public static BizException tooManyRequests(String msg) {
        return new BizException(429, msg);
    }
}
