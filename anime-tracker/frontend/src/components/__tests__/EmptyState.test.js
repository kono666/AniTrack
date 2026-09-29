import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import EmptyState from '../EmptyState.vue'

describe('EmptyState', () => {
  it('renders default message', () => {
    const wrapper = mount(EmptyState)
    expect(wrapper.text()).toContain('暂无数据')
  })

  it('renders custom message', () => {
    const wrapper = mount(EmptyState, {
      props: { message: '没有找到结果' }
    })
    expect(wrapper.text()).toContain('没有找到结果')
  })

  // 改前断言的是 `text()` 里含那个 emoji 字符 —— 图标是文字, 断言只能盯着它.
  // 现在图标由 `type` 决定, 断言盯着语义: 同一个 'search' 在两处(message 不同)
  // 渲染同一个图标, 而 'error' 渲染另一个. 这比钉一个字形更接近这个 prop 的约定.
  it('按 type 渲染对应的空态图标', () => {
    const search = mount(EmptyState, { props: { type: 'search' } })
    const error = mount(EmptyState, { props: { type: 'error' } })

    expect(search.find('.icon').attributes('data-type')).toBe('search')
    expect(search.find('svg').exists()).toBe(true)
    expect(error.find('.icon').attributes('data-type')).toBe('error')
    expect(error.find('svg').html()).not.toBe(search.find('svg').html())
  })

  it('没给 type 时退回默认空态', () => {
    const wrapper = mount(EmptyState)
    expect(wrapper.find('.icon').attributes('data-type')).toBe('empty')
  })

  it('renders action button when actionLabel provided', () => {
    const wrapper = mount(EmptyState, {
      props: { actionLabel: '去搜索' }
    })
    expect(wrapper.find('button').exists()).toBe(true)
    expect(wrapper.find('button').text()).toBe('去搜索')
  })

  it('does not render button when no actionLabel', () => {
    const wrapper = mount(EmptyState)
    expect(wrapper.find('button').exists()).toBe(false)
  })

  it('emits action event when button clicked', async () => {
    const wrapper = mount(EmptyState, {
      props: { actionLabel: '重试' }
    })
    await wrapper.find('button').trigger('click')
    expect(wrapper.emitted('action')).toBeTruthy()
  })

  // Profile.vue 往这个组件里塞过一个「去发现动漫」的链接, 而改前组件里
  // **没有 <slot>** —— 那段内容被静默丢掉了(不报错、不警告, 只是不出现).
  // 空态里少一个出口这种事没有人会去核对, 所以钉一条用例.
  it('渲染默认插槽里的内容', () => {
    const wrapper = mount(EmptyState, {
      props: { message: '还没有追番记录' },
      slots: { default: '<a href="/" class="go">去发现动漫</a>' },
    })

    expect(wrapper.find('.go').exists()).toBe(true)
    expect(wrapper.text()).toContain('去发现动漫')
  })

  it('没有插槽内容时不会多渲染出空元素', () => {
    const wrapper = mount(EmptyState)
    expect(wrapper.find('a').exists()).toBe(false)
  })
})
