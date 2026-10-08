package com.health.social.recommend.event;

/**
 * 文章发布事件（推荐模块的接入点）。
 *
 * <p><b>这是推荐模块对既有代码唯一的"侵入"</b>：{@code ArticleService.publish()}
 * 在事务提交前多发一行 {@code publishEvent}。选择 Spring 事件而不是直接调用推荐服务，原因：
 * <ol>
 *   <li><b>解耦</b>：发布方（文章服务）不需要知道有推荐模块存在，删掉推荐模块也不会编译失败；</li>
 *   <li><b>可扩展</b>：以后搜索、审核、数仓都可以各自监听同一事件，发布方零改动；</li>
 *   <li><b>事务语义清晰</b>：事件同步派发给 {@code @EventListener}，与 publish 同一事务；
 *       若推荐侧索引失败，不会影响文章落库（监听方自行 try/catch 降级）。</li>
 * </ol>
 *
 * <p>另外有一条<b>兜底路径</b>：{@code CandidatePoolMaintainer} 会按 create_time 水位线
 * 定时增量扫描 {@code t_article}。即使事件丢失（例如服务重启、消息未派发），
 * 候选池最终也会被补齐 —— 这正是"推荐模块可以完全独立运行"的保证。
 *
 * @param articleId     文章 ID
 * @param authorId      作者 ID
 * @param publishTimeMs 发布时间（毫秒），作为候选池与各索引的 ZSet score
 */
public record ArticlePublishedEvent(long articleId, long authorId, long publishTimeMs) {
}
