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
