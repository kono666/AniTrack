/**
 * 「这部番一共多少集」在本站唯一说得清的答案。
 *
 * 为什么要专门有个函数: 这个数有两个来源, 而且**两个都不是总能拿到** ——
 *
 *   declared 后端 `totalEpisodes`, 来自 Bangumi 条目接口的 `total_episodes`.
 *            是官方口径, 但**上游对绝大多数条目就是填 0**(＝官方没公布总集数).
 *            2026-10-02 实测: 29379 条里 29322 条是 0, 只有 56 条 > 0.
 *   local    后端 `episodeTotal`, 我们这边真收齐的剧集条数(整批成功回源后才写).
 *            与 declared **不是一回事**: 长篇上恒为 declared 更小(犬夜叉 181 vs 167).
 *            详情页上它就在眼前 —— 那一排剧集瓷砖的个数.
 *
 * 所以顺序是**声明值优先、没有才用本地条数**, 不能反过来:
 *   · 声明值是官方口径, 而且一个正在连载的番本地只收到已播的那几集,
 *     拿本地条数当"一共多少集"会把上限压在已播集数上;
 *   · 声明值不会因为本地剧集列表过期而变小, 本地条数会。
 *
 * 返回值 **0 表示"不知道"**, 不是"0 集". 这个区分是有代价的 —— 每一处调用都得自己判 0:
 *   `ProgressBar` 在 total 为 0 时整根不渲染(不是画一条 0% 的: "一集都没看"与
 *   "不知道一共多少集"是两件事); `clampProgress` 在 0 时不封顶。
 *
 * 改前这个判断散在三处、写法还各不相同(Home 与 Profile 用 `item.totalEpisodes` 的真值,
 * AnimeDetail 用 `subject.totalEpisodes || 999`), 于是同一个根因在三个页面上表现出
 * 三种症状 —— 前两处进度条整根不画, 第三处封顶整个失效(12 集的番能存 18)。
 * 修法是把这个数收敛到一处, 三个消费点都来问它。
 */

/**
 * @param {*} declared 后端 `totalEpisodes`(条目接口的声明值)
 * @param {*} local    后端 `episodeTotal` 或详情页的 `episodes.length`(本地收齐的条数)
 * @returns {number} 可用于当分母的集数; **0 表示不知道** —— 调用方必须自己判
 */
export function effectiveEpisodes(declared, local) {
  const d = Number(declared)
  if (Number.isFinite(d) && d > 0) return d
  const l = Number(local)
  return Number.isFinite(l) && l > 0 ? l : 0
}
