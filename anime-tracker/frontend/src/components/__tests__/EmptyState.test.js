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
})
