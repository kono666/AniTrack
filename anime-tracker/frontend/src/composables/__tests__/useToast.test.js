import { describe, it, expect, beforeEach, vi, afterEach } from 'vitest'
import { useToast, showToast, removeToast } from '../useToast'

/**
 * 这一组测的是「提示状态在模块作用域」这件事本身.
 *
 * 改前状态长在 Toast.vue 里并通过 window.$toast 暴露, 所以最要紧的一条是:
 * 任意两个调用点看到的必须是同一份列表 —— 否则提示要么根本不显示(组件那份
 * 是空的), 要么显示在谁也看不到的地方.
 */
describe('useToast', () => {
  beforeEach(() => {
    // 模块作用域的状态在同一个测试文件里是共享的, 每个用例前清干净
    for (const item of [...useToast().items.value]) removeToast(item.id)
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('两个调用点看到的是同一份列表', () => {
    const a = useToast()
    const b = useToast()

    a.show('保存成功', 'success')

    expect(b.items.value).toHaveLength(1)
    expect(b.items.value[0].message).toBe('保存成功')
    expect(b.items.value[0].type).toBe('success')
  })

  it('到时间自动消失', () => {
    vi.useFakeTimers()
    showToast('已保存', 'success', 100)
    expect(useToast().items.value).toHaveLength(1)

    vi.advanceTimersByTime(99)
    expect(useToast().items.value).toHaveLength(1)

    vi.advanceTimersByTime(1)
    expect(useToast().items.value).toHaveLength(0)
  })

  it('提前关掉之后, 定时器也要清掉', () => {
    vi.useFakeTimers()
    showToast('已保存')
    const id = useToast().items.value[0].id

    removeToast(id)

    expect(useToast().items.value).toHaveLength(0)
    // 不清定时器的话, 它到点还会再来删一次(此时无害), 但定时器本身就是泄漏
    expect(vi.getTimerCount()).toBe(0)
  })

  it('duration 传 0 表示不自动消失', () => {
    vi.useFakeTimers()
    showToast('需要用户处理的提示', 'warning', 0)

    vi.advanceTimersByTime(60_000)

    expect(useToast().items.value).toHaveLength(1)
  })

  it('带行动按钮的提示把 action 一起存下来', () => {
    const handler = vi.fn()
    showToast('追番失败', 'error', 3500, { label: '重试', handler })

    const item = useToast().items.value[0]
    expect(item.action.label).toBe('重试')
    item.action.handler()
    expect(handler).toHaveBeenCalled()
  })
})
