package com.health.social.config;

import com.health.social.common.UserContext;
import com.health.social.common.UserLevel;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 演示用鉴权：从请求头解析用户身份写入 ThreadLocal。
 * 真实环境由网关完成 JWT 校验后透传。
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Value("${health.debug-default-user-id:20001}")
    private long defaultUserId;

    @Value("${health.debug-default-level:0}")
    private int defaultLevel;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                long userId = parseLong(request.getHeader("X-User-Id"), defaultUserId);
                int level = parseInt(request.getHeader("X-User-Level"), defaultLevel);
                UserContext.set(userId, UserLevel.of(level));
                return true;
            }

            @Override
            public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                        Object handler, Exception ex) {
                UserContext.clear();
            }
        }).addPathPatterns("/**");
    }

    private static long parseLong(String v, long def) {
        if (v == null || v.isBlank()) {
            return def;
        }
        try {
            return Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static int parseInt(String v, int def) {
        if (v == null || v.isBlank()) {
            return def;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
