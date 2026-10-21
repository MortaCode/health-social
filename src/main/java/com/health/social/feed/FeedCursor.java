package com.health.social.feed;

import org.springframework.data.redis.core.StringRedisTemplate;

import java.math.BigDecimal;
import java.util.Set;

/**
 * Feed 游标的解析 / 格式化 / 同分组前缀计数。
 *
 * <h3>格式</h3>
 * <pre>
 *   cursor = "score:articleId"      例：1761000000000:1846231587557281793
 * </pre>
 *
 * <p>为什么必须是复合游标：{@code score} 取自 {@code article.create_time}，
 * 而 {@code t_article.create_time} 是 {@code DATETIME}（秒级精度），
 * 同一秒内发布的所有帖子 score <b>完全相同</b>。只用时间戳做游标时，
 * 这一秒的帖子在翻页边界上必然重复或丢失，必须靠 {@code articleId} 做二级排序键。
 *
 * <h3>为什么用 offset 而不是"取回来再过滤"</h3>
 * <p>同一 score 分组里的条目，上一页可能只返回了一部分。翻页时如果仍然从
 * 分组头部开始取、取回来再在内存里过滤，那么被过滤掉的条目会白白占掉
 * {@code count} 配额，导致每页固定少几条（分组越大少得越多）。
 * 正确做法是先用 {@link #sameScorePrefix} 算出"已返回前缀"的长度，
 * 直接把它作为 ZREVRANGEBYSCORE 的 offset 跳过。
 */
public final class FeedCursor {

    private FeedCursor() {
    }

    /**
     * 解析游标，格式非法返回 {@code null}（调用方按首页处理，而不是抛异常让请求失败）
     */
    public static Parsed parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        // 用 lastIndexOf 而不是 split(":")：articleId 理论上可能是负数，split 会产生多余分段
        int sep = raw.lastIndexOf(':');
        if (sep <= 0 || sep == raw.length() - 1) {
            return null;
        }
        try {
            double score = Double.parseDouble(raw.substring(0, sep).trim());
            long articleId = Long.parseLong(raw.substring(sep + 1).trim());
            return new Parsed(score, articleId);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 格式化游标。
     *
     * <p>score 用 {@link BigDecimal#toPlainString()} 输出，<b>不要</b>写成 {@code (long) score}：
     * 强转会截断小数位，现在 score 是整毫秒看不出问题，但将来若首页换成热度分（如 0.837），
     * 游标会被截成整数，翻页开始跳条重条，而且不报任何错。
     */
    public static String format(double score, long articleId) {
        String s = BigDecimal.valueOf(score).stripTrailingZeros().toPlainString();
        return s + ":" + articleId;
    }

    /**
     * 统计某个 ZSet 里「score 等于游标 score 且 articleId &gt;= 游标 articleId」的条数，
     * 即上一页已经返回过、本页必须跳过的同分组前缀长度。
     *
     * <p>代价：一次 ZRANGEBYSCORE 读取该 score 分组。分组的实际含义是"同一秒内发布的帖子"，
     * 量级很小；即使遇到批量导入把某一秒塞满，也只是一次可控的读，不会退化成全表扫描。
     */
    public static long sameScorePrefix(StringRedisTemplate redis, String key,
                                       double score, long articleId) {
        Set<String> members = redis.opsForZSet().rangeByScore(key, score, score);
        if (members == null || members.isEmpty()) {
            return 0L;
        }
        long count = 0L;
        for (String m : members) {
            try {
                if (Long.parseLong(m) >= articleId) {
                    count++;
                }
            } catch (NumberFormatException ignore) {
                // 脏数据，跳过
            }
        }
        return count;
    }

    /**
     * 解析结果
     *
     * @param score     排序分（发帖时间戳，毫秒）
     * @param articleId 同分组内的二级排序键
     */
    public record Parsed(double score, long articleId) {
    }
}
