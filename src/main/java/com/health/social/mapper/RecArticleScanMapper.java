package com.health.social.mapper;

import com.health.social.entity.Article;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 推荐模块专用的文章增量扫描 Mapper。
 *
 * <h3>为什么不直接复用 ArticleMapper</h3>
 * <p>{@code ArticleMapper} 属于文章/推流侧，推荐模块不该往里面加方法 ——
 * 这是"新开模块不污染既有模块"的边界纪律。本接口是纯新增文件，
 * 且只做只读扫描，不承担任何写入。
 *
 * <h3>为什么用 (create_time, id) 双键游标，而不是只按 create_time</h3>
 * <p>{@code t_article.create_time} 是 DATETIME（秒精度），而文章是批量发布的 ——
 * 同一秒内产生几十篇文章非常常见。如果只用 {@code WHERE create_time > :watermark}：
 * <ul>
 *   <li>用 {@code >}：同一秒内被截断的剩余文章会被<b>永久跳过</b>；</li>
 *   <li>用 {@code >=}：同一秒内的整批文章会被反复扫描，形成死循环。</li>
 * </ul>
 * 加上 id 作为第二排序键（{@code (create_time, id) > (wm, wmId)} 的字典序比较）后，
 * 游标是严格单调的，既不会漏也不会重复 —— 这是分页扫描的标准做法（keyset pagination）。
 */
public interface RecArticleScanMapper {

    /**
     * 扫描 {@code (create_time, id)} 严格大于游标的文章，按游标顺序升序返回。
     *
     * @param createTime 上次扫描到的 create_time
     * @param id         上次扫描到的 id（同一秒内的第二排序键）
     * @param limit      本批最多返回条数
     */
    List<Article> scanAfter(@Param("createTime") LocalDateTime createTime,
                            @Param("id") Long id,
                            @Param("limit") int limit);
}
