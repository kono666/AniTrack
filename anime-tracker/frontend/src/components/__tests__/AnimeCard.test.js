import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'

import AnimeCard from '../AnimeCard.vue'
import { COVER_FALLBACK_CARD } from '../../utils/fallbackImg'

/**
 * 卡片是整站最常用的入口, 而它改前只有一个 @click.
 *
 * 后果对键盘和读屏用户是"首页那一屏卡片全都到不了": tab 会直接跳过它们,
 * 读屏软件也不说这是能按的东西. 这类问题在鼠标下永远看不出来 ——
 * 所以只能靠断言把 role/tabindex/按键这三件事钉住.
 *
 * 空格和回车都要测: role=button 的约定是两者都触发. 只写回车的话,
 * "按空格没反应"会成为一个只有键盘用户才会遇到的小 bug, 而它不会有人报.
 */

const router = createRouter({
  history: createMemoryHistory(),
  routes: [
    { path: '/', component: { template: '<div />' } },
    { path: '/anime/:id', component: { template: '<div />' } },
  ],
})

const ANIME = {
  id: 42,
  nameCn: '进击的巨人',
  name: 'Shingeki no Kyojin',
  type: 'TV',
  date: '2013-04-07',
  totalEpisodes: 25,
  rating: { score: 9.1 },
}

function mountCard(anime = ANIME) {
  return mount(AnimeCard, { props: { anime }, global: { plugins: [router] } })
}

describe('AnimeCard 的可访问性与占位图', () => {
  // 每条用例都从首页开始, 否则上一条跳走的路径会留在 router 上.
  // 跳转本身是异步的: 断言 currentRoute 之前必须把微任务放完, 只 trigger 是看不到的
  beforeEach(async () => {
    await router.push('/')
    await router.isReady()
  })

  it('是键盘可达的按钮语义', () => {
    const card = mountCard().find('.anime-card')
    expect(card.attributes('role')).toBe('button')
    expect(card.attributes('tabindex')).toBe('0')
  })

  it('回车能打开详情', async () => {
    const wrapper = mountCard()
    await wrapper.find('.anime-card').trigger('keydown.enter')
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/anime/42')
  })

  it('空格也能打开详情', async () => {
    const wrapper = mountCard()
    await wrapper.find('.anime-card').trigger('keydown.space')
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/anime/42')
  })

  it('点击照旧(这一批不该把鼠标那条路弄坏)', async () => {
    const wrapper = mountCard()
    await wrapper.find('.anime-card').trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/anime/42')
  })

  it('封面加载失败时换成占位图, 而不是留一个破图', async () => {
    const wrapper = mountCard({ ...ANIME, images: { large: 'https://example.com/42.jpg' } })
    expect(wrapper.find('.anime-card-img').attributes('src')).toBe('https://example.com/42.jpg')

    await wrapper.find('.anime-card-img').trigger('error')

    // 占位图来自 utils/fallbackImg.js —— 五个组件原先各写了一份, 还都不一样
    expect(wrapper.find('.anime-card-img').attributes('src')).toBe(COVER_FALLBACK_CARD)
    expect(COVER_FALLBACK_CARD.startsWith('data:image/svg+xml,')).toBe(true)
  })
})
