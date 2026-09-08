package com.health.social.config;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.type.TypeFactory;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.support.converter.DefaultJackson2JavaTypeMapper;
import org.springframework.amqp.support.converter.MessageConversionException;
import org.springframework.util.ClassUtils;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * 信任包「前缀匹配」的 {@link DefaultJackson2JavaTypeMapper} 子类。
 *
 * <h3>为什么需要它</h3>
 * Spring AMQP 4.x 原生的 {@code setTrustedPackages} / {@code addTrustedPackages}
 * 用的是<b>精确包名 equals 匹配</b>：它先对类名取 {@code getPackageName()}，
 * 再与信任列表做相等比较。于是：
 * <pre>
 *   类名   com.health.social.like.LikeEvent  ->  getPackageName -> com.health.social.like
 *   信任包 com.health.social.*                ->  精确 equals  -> 永远不相等
 * </pre>
 * 导致反序列化直接抛 {@code IllegalArgumentException: ... is not in the trusted packages}，
 * 通配符 {@code com.health.social.*} 在这里<b>根本不起作用</b>。
 *
 * <h3>本类的做法</h3>
 * 覆写 {@code toJavaType(MessageProperties)}（该入口为 public，可安全覆写），
 * 对 {@code __TypeId__} 头做<b>前缀匹配</b>：只要类名落在 {@code com.health.social}
 * 子树下（或 {@code java.util} / {@code java.lang}）即视为可信并解析；
 * 其它类型回退到父类默认行为（{@code java.util/java.lang} 仍按官方默认放行，
 * 其余抛安全异常）。对后续新增消息包天然兼容，无需逐个列举子包。
 */
public class PrefixTrustedClassMapper extends DefaultJackson2JavaTypeMapper {

    private final Set<String> trustedPrefixes = new HashSet<>();

    public PrefixTrustedClassMapper(String... prefixes) {
        trustedPrefixes.add("java.util");
        trustedPrefixes.add("java.lang");
        if (prefixes != null) {
            trustedPrefixes.addAll(Arrays.asList(prefixes));
        }
    }

    private boolean isTrusted(String className) {
        String pkg = ClassUtils.getPackageName(className).replaceFirst("\\[L", "");
        for (String prefix : trustedPrefixes) {
            if ("*".equals(prefix) || pkg.equals(prefix) || pkg.startsWith(prefix + ".")) {
                return true;
            }
        }
        return false;
    }

    @Override
    public JavaType toJavaType(MessageProperties properties) {
        Object typeIdObj = properties.getHeader("__TypeId__");
        if (typeIdObj instanceof String typeId && isTrusted(typeId)) {
            try {
                Class<?> clazz = ClassUtils.forName(typeId, obtainClassLoader());
                return TypeFactory.defaultInstance().constructType(clazz);
            } catch (ClassNotFoundException | LinkageError e) {
                throw new MessageConversionException("Failed to resolve trusted type " + typeId, e);
            }
        }
        // 非前缀信任的类型（如 java.lang/java.util 或恶意类型）交给父类按官方默认处理
        return super.toJavaType(properties);
    }

    private ClassLoader obtainClassLoader() {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        return cl != null ? cl : getClass().getClassLoader();
    }
}
