import { describe, it, expect, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { nextTick } from 'vue'
import Toast from '../Toast.vue'
import { useToast, showToast, removeToast } from '../../composables/useToast'

/**
 * Toast.vue 必须是"渲染 composable 里那份状态"的组件.
 *
 * 如果哪天它又自己声明一份 ref([]), 应用里不会报任何错 —— 只是所有提示都
 * 永远不显示. 这种"静默失效"正是要在这里钉住的.
 */
describe('Toast.vue', () => {
  beforeEach(() => {
    for (const item of [...useToast().items.value]) removeToast(item.id)
  })

  function mountToast() {
    // Teleport 的内容默认渲染到 body, 这里换成就地渲染方便断言
    return mount(Toast, { global: { stubs: { teleport: true } } })
  }

  it('渲染 composable 里的提示, 并带上类型样式', async () => {
    const wrapper = mountToast()

    showToast('保存成功', 'success')
    await nextTick()

    expect(wrapper.text()).toContain('保存成功')
    expect(wrapper.find('.toast-success').exists()).toBe(true)
  })

  it('点关闭按钮移除这条提示', async () => {
    const wrapper = mountToast()
    showToast('保存成功', 'success')
    await nextTick()

    await wrapper.find('.toast-close').trigger('click')

    expect(wrapper.text()).not.toContain('保存成功')
    expect(useToast().items.value).toHaveLength(0)
  })

  it('带行动按钮的提示渲染出按钮并调用它', async () => {
    const handler = vi.fn()
    const wrapper = mountToast()
    showToast('追番失败', 'error', 3500, { label: '重试', handler })
    await nextTick()

    await wrapper.find('.toast-action').trigger('click')

    expect(handler).toHaveBeenCalled()
    // 点完就收起这条提示
    expect(useToast().items.value).toHaveLength(0)
  })
})
