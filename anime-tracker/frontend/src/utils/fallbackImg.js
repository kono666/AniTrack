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

/* 下面这几个色值是全项目**唯一没法 token 化**的地方: 它们在一个 SVG data URI
   里, CSS 变量进不去. 所以 --bg-secondary / --border / --input-border 改值时
   必须手动同步这里 —— 按新旧中性阶的**同一档位**对应:
     #18181b(zinc-900) → #171514  = --bg-secondary
     #27272a(zinc-800) → #332d2d  = --border
     #3f3f46(zinc-700) → #423b3a  = --input-border
   一一对应而不是重新挑色, 是为了保持这块占位图原本的明暗关系不变:
   换配色不该顺手把占位图也换个样子. */

/** 通用占位: 深灰底 + 一行 "No Cover". 用于列表、轮播、缩略图. */
export const COVER_FALLBACK = svg(
  '<rect width="300" height="400" rx="8"/>' +
  '<text x="150" y="200" text-anchor="middle" fill="#423b3a" font-size="16">No Cover</text>'
)

/**
 * 大封面占位: 深底 + 播放标记 + 「暂无封面」. 用于详情页头图、番剧卡片.
 *
 * 改前那个标记是个 48px 的场记板 emoji. 换成矢量画出来的圆+三角形, 两个理由:
 *   1. 字符的形状取决于系统装了什么 emoji 字体 —— 同一个占位图在 Windows 和
 *      macOS 上不是同一个东西, 而这是**图**, 本该到哪都一样;
 *   2. 它的基线在 y=215、字号 48, 也就是实际占了大约 176–215 这一段, 而上面
 *      「暂无封面」那行的基线在 y=195 —— 两者是叠着的. 现在标记挪到文字下面,
 *      各自有各自的位置.
 */
export const COVER_FALLBACK_CARD = svg(
  '<rect width="300" height="400" rx="8"/>' +
  '<text x="150" y="170" text-anchor="middle" fill="#423b3a" font-size="14">暂无封面</text>' +
  '<circle cx="150" cy="225" r="30" fill="none" stroke="#332d2d" stroke-width="2"/>' +
  '<path d="M141 210 L163 225 L141 240 Z" fill="#332d2d"/>'
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
    `<svg xmlns="http://www.w3.org/2000/svg" width="300" height="400" fill="#171514">${body}</svg>`
  )
}
