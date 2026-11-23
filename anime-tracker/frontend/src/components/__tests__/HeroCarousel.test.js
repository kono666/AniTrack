import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { nextTick } from 'vue'
import { createRouter, createMemoryHistory } from 'vue-router'
import HeroCarousel from '../HeroCarousel.vue'

/**
 * 新一轮首屏（12 部静态片单）要钉住的四件事：
 *
 * 1. **计时器幂等** —— 原型里 start() 不检查已有计时器，而 focusin/focusout 会冒泡，
 *    用 Tab 在轮播里换元素时 setInterval 会越叠越多，表现为「有时 6 秒一换、
 *    有时 2 秒一换」。这条是回归测试：连续 5 次焦点进出之后，推进 6 秒只能前进 1 张。
 * 2. **悬停暂停**：鼠标在图上时不该自己翻页。
 * 3. **手动切换重新计时**：点完箭头，新一屏要完整停留 6 秒。
 * 4. **减少动效 / 卸载**：不自动播、不留计时器。
 */

const router = createRouter({
  history: createMemoryHistory(),
  routes: [{ path: '/', component: { template: '<div />' } }],
})

function mountHero() {
  return mount(HeroCarousel, { global: { plugins: [router] } })
}

function activeIndex(wrapper) {
  const slides = wrapper.findAll('.slide')
  for (let i = 0; i < slides.length; i++) {
    if (slides[i].classes().includes('active')) return i
  }
  return -1
}

function stubMatchMedia(matches) {
  vi.stubGlobal('matchMedia', () => ({ matches, addEventListener() {}, removeEventListener() {} }))
}

function setHidden(hidden) {
  Object.defineProperty(document, 'hidden', { value: hidden, configurable: true })
}

describe('HeroCarousel 首屏轮播', () => {
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

  it('渲染 12 张与 12 个指示点，第一张默认激活', () => {
    const wrapper = mountHero()
    expect(wrapper.findAll('.slide')).toHaveLength(12)
    expect(wrapper.findAll('.dot')).toHaveLength(12)
    expect(activeIndex(wrapper)).toBe(0)
    wrapper.unmount()
  })

  it('自动播放每 6 秒前进一张', async () => {
    const wrapper = mountHero()
    await nextTick()
    expect(activeIndex(wrapper)).toBe(0)

    vi.advanceTimersByTime(6000)
    await nextTick()
    expect(activeIndex(wrapper)).toBe(1)

    vi.advanceTimersByTime(6000)
    await nextTick()
    expect(activeIndex(wrapper)).toBe(2)
    wrapper.unmount()
  })

  it('焦点在轮播里反复进出也不会叠加计时器（回归：以前会变成 2 秒一换）', async () => {
    const wrapper = mountHero()
    await nextTick()

    const hero = wrapper.find('.hero')
    for (let i = 0; i < 5; i++) {
      await hero.trigger('focusin')
      // relatedTarget 在轮播外面 = 焦点真的离开了
      await hero.trigger('focusout', { relatedTarget: document.body })
    }

    vi.advanceTimersByTime(6000)
    await nextTick()
    // 叠加 5 个计时器的话这里会是 5；正确实现只前进 1 张
    expect(activeIndex(wrapper)).toBe(1)
    wrapper.unmount()
  })

  it('焦点在轮播内部移动时不恢复计时', async () => {
    const wrapper = mountHero()
    await nextTick()

    const hero = wrapper.find('.hero')
    const inner = wrapper.find('.btn')

    await hero.trigger('focusin')
    // 焦点从「查看详情」移到「加入追番」——仍在 hero 里
    await hero.trigger('focusout', { relatedTarget: inner.element })

    vi.advanceTimersByTime(6000)
    await nextTick()
    expect(activeIndex(wrapper)).toBe(0)   // 仍是暂停状态
    wrapper.unmount()
  })

  it('鼠标悬停时暂停，移出后恢复', async () => {
    const wrapper = mountHero()
    await nextTick()

    const hero = wrapper.find('.hero')
    await hero.trigger('mouseenter')
    vi.advanceTimersByTime(12_000)
    await nextTick()
    expect(activeIndex(wrapper)).toBe(0)

    await hero.trigger('mouseleave')
    vi.advanceTimersByTime(6000)
    await nextTick()
    expect(activeIndex(wrapper)).toBe(1)
    wrapper.unmount()
  })

  it('点箭头会切过去，并重新计时 6 秒', async () => {
    const wrapper = mountHero()
    await nextTick()

    // 先走 3 秒：距下次自动切换只剩 3 秒
    vi.advanceTimersByTime(3000)
    await wrapper.find('.arrow.next').trigger('click')
    expect(activeIndex(wrapper)).toBe(1)

    // 点击后 5.9 秒仍在第 2 张；没重新计时的话第 3 秒就该切走了
    vi.advanceTimersByTime(5900)
    await nextTick()
    expect(activeIndex(wrapper)).toBe(1)

    vi.advanceTimersByTime(100)
    await nextTick()
    expect(activeIndex(wrapper)).toBe(2)
    wrapper.unmount()
  })

  it('点指示点直接跳过去', async () => {
    const wrapper = mountHero()
    await nextTick()
    await wrapper.findAll('.dot')[7].trigger('click')
    expect(activeIndex(wrapper)).toBe(7)
    wrapper.unmount()
  })

  it('页面切到后台就暂停，切回来再继续', async () => {
    const wrapper = mountHero()
    await nextTick()

    setHidden(true)
    document.dispatchEvent(new Event('visibilitychange'))
    vi.advanceTimersByTime(6000)
    await nextTick()
    expect(activeIndex(wrapper)).toBe(0)

    setHidden(false)
    document.dispatchEvent(new Event('visibilitychange'))
    vi.advanceTimersByTime(6000)
    await nextTick()
    expect(activeIndex(wrapper)).toBe(1)
    wrapper.unmount()
  })

  it('系统开了「减少动效」就不自动播放', async () => {
    stubMatchMedia(true)
    const wrapper = mountHero()
    vi.advanceTimersByTime(6000)
    await nextTick()
    expect(activeIndex(wrapper)).toBe(0)
    wrapper.unmount()
  })

  it('组件卸载后不再有定时器在跑', async () => {
    const wrapper = mountHero()
    await nextTick()
    wrapper.unmount()
    expect(vi.getTimerCount()).toBe(0)
  })

  it('两组各 6 部：第 7 张起是热度推荐', () => {
    const wrapper = mountHero()
    const eyebrow = (i) => wrapper.findAll('.slide')[i].find('.eyebrow').text()
    expect(eyebrow(0)).toContain('站主推荐')
    expect(eyebrow(5)).toContain('站主推荐')
    expect(eyebrow(6)).toContain('热度推荐')
    expect(eyebrow(11)).toContain('热度推荐')
    wrapper.unmount()
  })
})
