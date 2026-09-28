/**
 * 封面加载失败时用的占位图.
 *
 * 为什么要有这个文件: 这段 data URI 原先在 5 个组件里各写了一份(HeroBanner、
 * AnimeCard、Home、Profile、AnimeDetail), 而且**五份里有四份长得不一样** ——
 * 圆角一会儿有一会儿没有、文案在「No Cover / 暂无 / 暂无封面」之间摇摆、
 * 底色一个是 #18181b 一个是 #1a1a2e. 没有哪一份是"对"的, 它们只是各自被改过
 * 一次. 复制粘贴的产物就是这样: 想统一改个底色, 得先找出到底有几份.
 *
 * 保留成两个常量而不是一个, 是因为它们确实服务于两种场合:
 *   * {@link COVER_FALLBACK} 用在列表/轮播的小格子里, 只有一行说明;
 *   * {@link COVER_FALLBACK_CARD} 用在大封面上(详情页头图、卡片封面),
 *     加一个大一点的图标, 空的面积大时不至于太寡淡.
 * 原先那 4 个变体里, 差异(RoundedRect 的 rx、文案)属于漂移, 这里按上面这条
 * 界线收敛成两个.
 */

/** 通用占位: 深灰底 + 一行 "No Cover". 用于列表、轮播、缩略图. */
export const COVER_FALLBACK = svg(
  '<rect width="300" height="400" rx="8"/>' +
  '<text x="150" y="200" text-anchor="middle" fill="#3f3f46" font-size="16">No Cover</text>'
)

/** 大封面占位: 深蓝底 + 🎬 + 「暂无封面」. 用于详情页头图、番剧卡片. */
export const COVER_FALLBACK_CARD = svg(
  '<rect width="300" height="400" rx="8"/>' +
  '<text x="150" y="195" text-anchor="middle" fill="#3f3f46" font-size="14">暂无封面</text>' +
  '<text x="150" y="215" text-anchor="middle" fill="#27272a" font-size="48">🎬</text>'
)

/**
 * 拼成可直接塞进 img src 的 data URI.
 *
 * encodeURIComponent 是必须的: 里面的 `#3f3f46` 这类颜色值带 `#`, 不编码的话
 * 浏览器会把 `#` 之后的部分当成 URL 的 fragment 丢掉, 图就变成一片空白 ——
 * 而且这个错误在本地开发时不一定看得出来(某些浏览器会宽容处理).
 * 中文字符同样需要编码, 所以整个字符串一起编, 不挑着编.
 */
function svg(body) {
  return 'data:image/svg+xml,' + encodeURIComponent(
    `<svg xmlns="http://www.w3.org/2000/svg" width="300" height="400" fill="#18181b">${body}</svg>`
  )
}
