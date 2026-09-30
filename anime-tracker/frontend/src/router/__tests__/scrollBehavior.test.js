import { describe, it, expect, vi, afterEach } from 'vitest'
import { scrollBehavior } from '../../router'

/**
 * 后退/前进时的滚动恢复.
 *
 * 这一组盯的是**时序**, 因为这里"看起来最自然"的写法在本项目里恰好是错的:
 * vue-router 的 handleScroll 是 `nextTick().then(() => scrollBehavior(...))` ——
 * 路由一变就在下一个微任务里滚, 而 App.vue 是 `<Transition mode="out-in">`,
 * 新页面要等旧页面走完 --dur(200ms)才挂载. 也就是说同步返回位置时, DOM 里
 * **还是旧页面**: 滚动作用在旧页面的高度上, 紧接着旧页面卸载、高度塌掉, 位置被
 * 夹回 0, 而且没有第二次机会(getSavedScrollPosition 读完就 delete).
 *
 * 所以"同步返回 savedPosition"和"返回一个推迟的 Promise"在浏览器里的区别不是
 * 风格问题, 是有没有效果的问题. 这里用假定时器把它钉住.
 */
describe('scrollBehavior', () => {
  afterEach(() => {
    vi.useRealTimers()
    vi.restoreAllMocks()
  })

  it('push 式导航(没有 savedPosition) 回顶, 且不带 behavior', async () => {
    // 回顶这条与改动前**逐字一致**(不带 behavior) —— 那是既有的观感, 本轮不动.
    // 去掉 behavior 这个字段是有意的: base.css 的 smooth 会让回顶也变成一段动画
    const result = scrollBehavior({}, {}, null)
    expect(result).toEqual({ top: 0 })
    expect(result.behavior).toBeUndefined()
  })

  it('后退时不立刻滚, 而是推迟到页面过渡结束之后', async () => {
    vi.useFakeTimers()
    const saved = { left: 0, top: 480 }
    const position = scrollBehavior({}, {}, saved)

    let settled = false
    position.then(() => { settled = true })
    await Promise.resolve()

    // 立刻滚 = 作用在旧页面上 = 等于没写(见文件头). 这条断言就是那种写法的红线
    expect(settled).toBe(false)

    vi.advanceTimersByTime(240) // --dur 兜底 200 + 40 的余量
    await expect(position).resolves.toEqual({ left: 0, top: 480, behavior: 'instant' })
  })

  it('behavior 是 instant 不是 auto', async () => {
    vi.useFakeTimers()
    const position = scrollBehavior({}, {}, { left: 0, top: 10 })
    vi.advanceTimersByTime(240)

    // 'auto' 的意思是「按 CSS 的 scroll-behavior 来」, 而 base.css 里写着
    // `html { scroll-behavior: smooth }` —— 用 'auto' 会在这里**平滑滚动**,
    // 那段动画正好和页面切换撞在一起. 看着像个无害的默认值, 其实是反的
    const result = await position
    expect(result.behavior).toBe('instant')
  })

  it('延迟时长取自 --dur, 而且是**调用时**读的', async () => {
    vi.useFakeTimers()
    // 模块在文件顶部就已经 import 过了. 现在才把 --dur 换成 400ms ——
    // 如果它是模块加载时读成常量的, 这里会走 200 那一档, 下面的断言就会红
    vi.spyOn(window, 'getComputedStyle').mockReturnValue({
      getPropertyValue: () => '400ms',
    })

    const position = scrollBehavior({}, {}, { left: 0, top: 100 })
    let settled = false
    position.then(() => { settled = true })

    vi.advanceTimersByTime(240) // 走完 200 那一档
    await Promise.resolve()
    expect(settled).toBe(false)

    vi.advanceTimersByTime(200) // 400 + 40
    await expect(position).resolves.toEqual({ left: 0, top: 100, behavior: 'instant' })
  })
})

/**
 * 同一条路径上只换了 query 时要不要回顶.
 *
 * 分类页把筛选条件写在 query 里, 而"点一个标签"不是"到了另一个地方": 回顶会把
 * 用户从他刚点的那排标签上扔回页面最上面, 而他点的那个标签在几百像素以下 ——
 * 这就是用户报的「点分类标签会自动跳到顶部, 但是分类出的动漫是在页面底部」.
 *
 * 例外按**路由** opt-in, 不写成全局的 `to.path === from.path`(最后两条就是钉
 * 这件事): 搜索页的翻页与"在 /search 上再搜一次"也是同 path 的 query 变化,
 * 那两处正是靠回顶让用户看到新结果的开头.
 */
describe('scrollBehavior: 同 path 只换 query', () => {
  /** 分类页: path 永远是 /tags, 区别只在 fullPath 上带不带 query */
  const tags = fullPath => ({
    path: '/tags',
    fullPath,
    meta: { scrollOnQueryChange: false },
  })

  afterEach(() => {
    vi.useRealTimers()
    vi.restoreAllMocks()
  })

  it('目标页声明了不管滚动 + 同 path + query 变了 → 一次都不滚', () => {
    // 返回 false 会被 vue-router 的 handleScroll 用 `position &&` 短路掉,
    // 也就是"什么都不发生"(而不是"滚到 0")
    const result = scrollBehavior(tags('/tags?tag=治愈'), tags('/tags'), null)
    expect(result).toBe(false)
  })

  it('同 path 但 query 一个字都没变 → 仍然回顶', () => {
    // 已经在 /tags 上再点一次导航栏「分类」: 那次仍然是"重新去这一页", 该回顶
    const result = scrollBehavior(tags('/tags'), tags('/tags'), null)
    expect(result).toEqual({ top: 0 })
  })

  it('从别的路径**进入**带标记的页面 → 照常回顶, 标记不误伤换页', () => {
    const result = scrollBehavior(
      tags('/tags?tag=治愈'),
      { path: '/', fullPath: '/' },
      null,
    )
    expect(result).toEqual({ top: 0 })
  })

  it('后退/前进优先于"这一页自己管滚动"', async () => {
    // 顺序不能反: 反过来的话, 同 path 的两个历史条目之间后退会被判成
    // "query 变了, 不滚", 恢复位置就丢了
    vi.useFakeTimers()
    const position = scrollBehavior(tags('/tags?tag=治愈'), tags('/tags'), { left: 0, top: 300 })

    let settled = false
    position.then(() => { settled = true })
    await Promise.resolve()
    expect(settled).toBe(false)

    vi.advanceTimersByTime(240)
    await expect(position).resolves.toEqual({ left: 0, top: 300, behavior: 'instant' })
  })

  it('搜索页同 path 的 query 变化仍然回顶(例外只给声明过的页面)', () => {
    const result = scrollBehavior(
      { path: '/search', fullPath: '/search?page=2' },
      { path: '/search', fullPath: '/search' },
      null,
    )
    expect(result).toEqual({ top: 0 })
  })

  it('meta 是 undefined 时不能当成"声明过"', () => {
    // 最裸的一次调用(两个空对象)必须仍然回顶. 这条是廉价的护栏, 守住
    // "没有 meta 的路由不许被例外规则波及" 这层意思.
    //
    // 注意它**不是** `=== false` 与 `!to.meta?.x` 之分的守卫 —— 实测过:
    // 改成 `!to.meta?.x` 之后这条照样绿(此时 to.path === from.path 为 true,
    // 但 to.fullPath !== from.fullPath 为 false, 整个条件仍然不成立),
    // 真正被打红的是上面那条「搜索页同 path 的 query 变化仍然回顶」.
    expect(scrollBehavior({}, {}, null)).toEqual({ top: 0 })
  })
})
