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

  it('renders custom icon', () => {
    const wrapper = mount(EmptyState, {
      props: { icon: '🔍' }
    })
    expect(wrapper.text()).toContain('🔍')
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
