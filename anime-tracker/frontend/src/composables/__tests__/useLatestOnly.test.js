import { describe, it, expect } from 'vitest'
import { useLatestOnly } from '../useLatestOnly'

/**
 * 「只认最后一次请求」的令牌.
 *
 * 用法上的硬要求(见模块注释)是: 过期分支必须 return, 且要放在收尾赋值之前.
 * 那不是这个模块能自己保证的, 只能靠调用点的用例盯(见 Search/Home 的竞态用例);
 * 这里只管令牌本身的三条性质.
 */
describe('useLatestOnly', () => {
  it('开新请求的那一刻, 旧令牌立即失效', () => {
    const { begin, isCurrent } = useLatestOnly()
    const first = begin()
    expect(isCurrent(first)).toBe(true)

    const second = begin()
    expect(isCurrent(second)).toBe(true)
    // 关键在「立即」: 不是等第二个请求回来了才作废, 而是它一发出,
    // 第一个就算过期 —— 否则两个请求同时在飞时仍然是谁先回谁说话
    expect(isCurrent(first)).toBe(false)
  })

  it('每次 begin 发出来的令牌都不一样', () => {
    const { begin } = useLatestOnly()
    const a = begin()
    const b = begin()
    expect(a).not.toBe(b)
  })

  it('两个实例互不干扰', () => {
    // 这条防的是"为了省事改成模块级单例": 那样 Search 的一次搜索会把 Home 的
    // 分类请求一并作废, 两个组件的请求互相踩, 而且症状是随机的
    const search = useLatestOnly()
    const tag = useLatestOnly()

    const searchToken = search.begin()
    const tagToken = tag.begin()

    expect(search.isCurrent(searchToken)).toBe(true)
    expect(tag.isCurrent(tagToken)).toBe(true)
    // 注: 不能拿 tagToken 去问 search —— 两个实例各数各的, 令牌之间没有可比性
  })
})
