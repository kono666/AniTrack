/**
 * 路由 query 的取值口径 —— 两个 view(Home / Search)共用一份.
 *
 * 为什么值得单独一个文件: 这两个页面现在都要「把筛选状态写进 URL, 再从 URL 读回来」,
 * 读的那一侧必须逐字一致, 否则会出现"这个页面能恢复、那个页面不能"的分裂.
 * 而 route.query 的值有三种形态, 直接拿来用是错的:
 *
 *   · 单个字符串 —— 正常情况
 *   · **字符串数组** —— `?tag=a&tag=b` 这种重复参数, vue-router 会解析成数组.
 *     数组是 truthy, 于是 `if (route.query.tag)` 会放它过去, 最后被拼成一个
 *     逗号串发给后端(或当作页码 NaN); 改前 Search.vue 的 `if (val)` 就是这个形状
 *   · undefined / null —— 参数不存在
 *
 * 所以这里把「取值」和「判合法」收在一处, 两边的行为由同一个函数决定.
 */

/** 只接受单个字符串; 数组 / undefined / null 一律当「没有这个参数」 */
export function strParam(raw) {
  return typeof raw === 'string' ? raw : ''
}

/**
 * 页码: 只有正整数才算数, 其余(缺省 / '0' / '-1' / 'abc' / 数组)一律回 1.
 *
 * 刻意不是「非法就拒绝整条 URL」: 手输错一个参数不该让整个页面变成错误页,
 * 回落到第 1 页是唯一不会让人卡住的解释. Home 与 Search 都是这个口径.
 */
export function pageParam(raw) {
  const n = Number.parseInt(strParam(raw), 10)
  return Number.isInteger(n) && n > 0 ? n : 1
}

/**
 * 逗号分隔的多选参数 → 数组. `?genre=mecha,fantasy` → `['mecha', 'fantasy']`.
 *
 * 分类浏览页的四个标签组走的是这一种写法, 而不是 `?genre=a&genre=b`: URL 更短、
 * 更好读, 读写两侧都只是 split / join. 上面 strParam 的注释里那段"数组形态是
 * 陷阱"在这里反过来用 —— 数组若真的出现, 说明这条 URL 不是本页写出来的,
 * 当作"没有这个参数"处理, 比按其中一种写法解析出半条筛选要诚实.
 *
 * 去重是必要的: 手改的 URL 里同一个 slug 出现两次, 会让请求里的标签名重复
 * (后端会去重, 但 URL 与选择状态两边对不上才是真麻烦).
 */
export function arrParam(raw) {
  const value = strParam(raw)
  if (!value) return []
  return [...new Set(value.split(',').filter(Boolean))]
}
