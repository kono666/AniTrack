import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { nextTick } from 'vue'
import { createRouter, createMemoryHistory } from 'vue-router'
import HeroBanner from '../HeroBanner.vue'

/**
 * 轮播有两件事要钉住:
 *
 * 1. **数据是后到的**. Home.vue 是先用空数组把组件挂上、接口回来才通过 props
 *    传进来的. 改前只在 onMounted 里判断一次 length > 1, 那时候数组还是空的 ——
 *    于是定时器从来没起来过, 首页那个轮播其实一直停在第一张(箭头能点, 所以
 *    看着像"能动"). 这类 bug 在页面上极难看出来: 不动也是一种合法状态.
 * 2. **该停的时候要停**: 页面切到后台、用户在系统里开了减少动效.
 */

const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/', component: { template: '<div />' } }] })

function items(n) {
  return Array.from({ length: n }, (_, i) => ({
    id: i + 1,
    nameCn: `番剧${i + 1}`,
    name: `Anime ${i + 1}`,
    images: { large: `https://example.com/${i + 1}.jpg` },
  }))
}

function mountHero(initial) {
  return mount(HeroBanner, { props: { items: initial }, global: { plugins: [router] } })
}

/** 轮播当前在第几张(读 transform, 与用户看到的一致) */
function slideOf(wrapper) {
  const style = wrapper.find('.hero-track').attributes('style') || ''
  const m = style.match(/translateX\(-(\d+)%\)/)
  return m ? Number(m[1]) / 100 : null
}

function stubMatchMedia(matches) {
  vi.stubGlobal('matchMedia', () => ({ matches, addEventListener() {}, removeEventListener() {} }))
}

function setHidden(hidden) {
  Object.defineProperty(document, 'hidden', { value: hidden, configurable: true })
}

describe('HeroBanner 轮播', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    stubMatchMedia(false)
    setHidden(false)
  })

  afterEach(() => {
    vi.useRealTimers()
    vi.unstubAllGlobals()
    setHidden(false)
  })

  it('数据后到也能开始轮播(改前定时器从来没起来过)', async () => {
    // 和 Home.vue 一样: 先空着挂上去
    const wrapper = mountHero([])
    expect(wrapper.find('.hero').exists()).toBe(false)

    await wrapper.setProps({ items: items(3) })
    await nextTick()
    expect(slideOf(wrapper)).toBe(0)

    vi.advanceTimersByTime(5000)
    await nextTick()
    expect(slideOf(wrapper)).toBe(1)

    vi.advanceTimersByTime(5000)
    await nextTick()
    expect(slideOf(wrapper)).toBe(2)

    // 到末尾绕回第一张
    vi.advanceTimersByTime(5000)
    await nextTick()
    expect(slideOf(wrapper)).toBe(0)
  })

  it('只有一张时不轮播', async () => {
    const wrapper = mountHero(items(1))
    vi.advanceTimersByTime(30_000)
    await nextTick()
    expect(slideOf(wrapper)).toBe(0)
  })

  it('页面切到后台就暂停, 切回来再继续', async () => {
    const wrapper = mountHero(items(3))
    await nextTick()

    setHidden(true)
    document.dispatchEvent(new Event('visibilitychange'))
    // 只推进一个周期: 推 30 秒的话正好是 6 次切换, 3 张图绕一圈又回到第 0 张 ——
    // 那样即使后台根本没暂停, 断言也是绿的(这条最初就是这么写错的)
    vi.advanceTimersByTime(5000)
    await nextTick()
    expect(slideOf(wrapper)).toBe(0)   // 后台期间一次都没转

    setHidden(false)
    document.dispatchEvent(new Event('visibilitychange'))
    vi.advanceTimersByTime(5000)
    await nextTick()
    expect(slideOf(wrapper)).toBe(1)
  })

  it('系统开了「减少动效」就不自动播放', async () => {
    stubMatchMedia(true)
    const wrapper = mountHero(items(3))
    // 同上: 推进的时长要避开"整圈"那个数, 否则不暂停也是绿的
    vi.advanceTimersByTime(5000)
    await nextTick()
    expect(slideOf(wrapper)).toBe(0)
  })

  it('点箭头会切过去, 并把 5 秒重新计时', async () => {
    const wrapper = mountHero(items(3))
    await nextTick()

    // 先走过 3 秒: 距下一次自动切换只剩 2 秒.
    // 这样"点击后有没有重新计时"才会有区别 —— 从 0 秒就点击的话,
    // 重新计时与否的下一次切换都落在第 5 秒
    vi.advanceTimersByTime(3000)
    await wrapper.find('.hero-arrow-right').trigger('click')
    expect(slideOf(wrapper)).toBe(1)

    // 距点击 4.9 秒还停在第 2 张: 没重新计时的话, 第 2 秒(总第 5 秒)就该切走了
    vi.advanceTimersByTime(4999)
    await nextTick()
    expect(slideOf(wrapper)).toBe(1)

    vi.advanceTimersByTime(1)
    await nextTick()
    expect(slideOf(wrapper)).toBe(2)
  })

  it('数据变空再变满时回到第一张(不会停在一个已经不存在的下标上)', async () => {
    const wrapper = mountHero(items(4))
    await nextTick()
    await wrapper.find('.hero-arrow-right').trigger('click')
    await wrapper.find('.hero-arrow-right').trigger('click')
    expect(slideOf(wrapper)).toBe(2)

    await wrapper.setProps({ items: [] })
    await nextTick()
    expect(wrapper.find('.hero').exists()).toBe(false)

    // 新数据只有两张, 而 current 还是 2 —— 不移回 0 的话 transform 会是 -200%,
    // 用户看到的是一个空框(轮播里没有第 3 张可显示)
    await wrapper.setProps({ items: items(2) })
    await nextTick()
    expect(slideOf(wrapper)).toBe(0)
  })

  it('组件卸载后不再有定时器在跑', async () => {
    const wrapper = mountHero(items(3))
    await nextTick()
    wrapper.unmount()
    expect(vi.getTimerCount()).toBe(0)
  })
})
