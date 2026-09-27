import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import AnimeChip from '../AnimeChip.vue'

const ANIME = {
  id: 42,
  name: '進撃の巨人',
  nameCn: '进击的巨人',
  cover: 'https://example.com/42.jpg',
  rating: 8.9,
  date: '2013-04-07',
  episodes: 25,
}

/**
 * router-link 在这里只是个「跳到详情页」的壳, 换成替身即可 ——
 * 真正要验的是卡片本身画了什么, 而不是路由能不能跳.
 */
const RouterLinkStub = {
  name: 'RouterLink',
  props: ['to'],
  template: '<a :href="to"><slot /></a>',
}

function mountChip(anime) {
  return mount(AnimeChip, {
    props: { anime },
    global: { stubs: { RouterLink: RouterLinkStub } },
  })
}

describe('AnimeChip', () => {
  it('links to the anime detail page', () => {
    const wrapper = mountChip(ANIME)
    expect(wrapper.findComponent(RouterLinkStub).props('to')).toBe('/anime/42')
  })

  it('prefers the Chinese title and keeps the original as a subtitle', () => {
    const wrapper = mountChip(ANIME)
    expect(wrapper.find('.anime-chip-title').text()).toBe('进击的巨人')
    expect(wrapper.find('.anime-chip-sub').text()).toBe('進撃の巨人')
  })

  it('omits the subtitle when the two titles are the same', () => {
    const wrapper = mountChip({ ...ANIME, nameCn: '进击的巨人', name: '进击的巨人' })
    expect(wrapper.find('.anime-chip-sub').exists()).toBe(false)
  })

  it('falls back to the original title when there is no Chinese one', () => {
    const wrapper = mountChip({ ...ANIME, nameCn: null })
    expect(wrapper.find('.anime-chip-title').text()).toBe('進撃の巨人')
    expect(wrapper.find('.anime-chip-sub').exists()).toBe(false)
  })

  it('renders the cover image', () => {
    const wrapper = mountChip(ANIME)
    expect(wrapper.find('.anime-chip-cover img').attributes('src')).toBe('https://example.com/42.jpg')
  })

  // 演示给别人看的时候, 一排碎图标比没有封面更糟: 用首字顶上至少还认得出是哪部
  it('shows the first title character when the cover is missing', () => {
    const wrapper = mountChip({ ...ANIME, cover: null })
    expect(wrapper.find('.anime-chip-cover img').exists()).toBe(false)
    expect(wrapper.find('.anime-chip-fallback').text()).toBe('进')
  })

  it('falls back to the letter when the image fails to load', async () => {
    const wrapper = mountChip(ANIME)
    await wrapper.find('.anime-chip-cover img').trigger('error')
    expect(wrapper.find('.anime-chip-fallback').text()).toBe('进')
  })

  it('formats the rating to one decimal', () => {
    const wrapper = mountChip({ ...ANIME, rating: 8 })
    expect(wrapper.find('.anime-chip-rating').text()).toContain('8.0')
  })

  it('hides a zero or missing rating instead of showing 0.0', () => {
    expect(mountChip({ ...ANIME, rating: 0 }).find('.anime-chip-rating').exists()).toBe(false)
    expect(mountChip({ ...ANIME, rating: null }).find('.anime-chip-rating').exists()).toBe(false)
  })

  it('accepts a rating sent as a string', () => {
    // 工具结果是通用 Map, 后端把分数序列化成什么样都可能
    expect(mountChip({ ...ANIME, rating: '9.25' }).find('.anime-chip-rating').text()).toContain('9.3')
  })

  it('shows the episode count for a user-side result', () => {
    const wrapper = mountChip(ANIME)
    expect(wrapper.find('.anime-chip-extra').text()).toBe('共 25 集')
  })

  it('prefers the tracking count when the row comes from the ops ranking', () => {
    const wrapper = mountChip({ ...ANIME, trackingCount: 318 })
    expect(wrapper.find('.anime-chip-extra').text()).toBe('318 人追')
  })

  it('renders the summary when the tool provided one', () => {
    const wrapper = mountChip({ ...ANIME, summary: '人类与巨人的战争' })
    expect(wrapper.find('.anime-chip-summary').text()).toBe('人类与巨人的战争')
  })

  it('renders with only an id and a name', () => {
    const wrapper = mountChip({ id: 1, name: '最低限度' })
    expect(wrapper.find('.anime-chip-title').text()).toBe('最低限度')
    expect(wrapper.find('.anime-chip-rating').exists()).toBe(false)
    expect(wrapper.find('.anime-chip-date').exists()).toBe(false)
  })
})
