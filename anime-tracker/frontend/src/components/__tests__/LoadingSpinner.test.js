import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import LoadingSpinner from '../LoadingSpinner.vue'

describe('LoadingSpinner', () => {
  it('renders default text', () => {
    const wrapper = mount(LoadingSpinner)
    expect(wrapper.text()).toContain('加载中...')
  })

  it('renders custom text', () => {
    const wrapper = mount(LoadingSpinner, {
      props: { text: '搜索中...' }
    })
    expect(wrapper.text()).toContain('搜索中...')
  })

  it('does not show text when noSpinner is true', () => {
    const wrapper = mount(LoadingSpinner, {
      props: { noSpinner: true, text: '加载中...' }
    })
    expect(wrapper.find('.spinner').exists()).toBe(false)
  })

  it('shows spinner by default', () => {
    const wrapper = mount(LoadingSpinner)
    expect(wrapper.find('.spinner').exists()).toBe(true)
  })
})
