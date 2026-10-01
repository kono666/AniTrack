import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import ProgressBar from '../ProgressBar.vue'

/**
 * 进度条只有一条真逻辑: 「不知道一共多少集」与「一集都没看」必须长得不一样.
 *
 * 两者的数值都是 0, 而页面上它们的意思完全相反 —— 一部没缓存过的番(后端不发
 * totalEpisodes 这个键)不该顶着一根 0% 的条, 那看起来像"你看了一整部但进度是零".
 */
function fillWidth(wrapper) {
  return wrapper.find('.pb-fill').attributes('style') || ''
}

describe('ProgressBar', () => {
  it('按 value/total 画出百分比', () => {
    const wrapper = mount(ProgressBar, { props: { value: 5, total: 12 } })
    expect(fillWidth(wrapper)).toContain('width: 42%')  // 41.67 四舍五入
  })

  it('total 为 null 时整根不渲染(而不是画一条 0%)', () => {
    // 后端在"本地没缓存过这部番"时**不发**这个键, 到前端就是 undefined;
    // 而 Vue 的 prop default 只对 undefined 生效, null 会原样传进来
    expect(mount(ProgressBar, { props: { value: 3, total: null } }).find('.pb-bar').exists())
      .toBe(false)
    expect(mount(ProgressBar, { props: { value: 3, total: 0 } }).find('.pb-bar').exists())
      .toBe(false)
    expect(mount(ProgressBar, { props: { value: 3, total: -1 } }).find('.pb-bar').exists())
      .toBe(false)
  })

  it('total 已知但一集没看: 条在, 宽度是 0%', () => {
    const wrapper = mount(ProgressBar, { props: { value: 0, total: 12 } })
    expect(wrapper.find('.pb-bar').exists()).toBe(true)
    expect(fillWidth(wrapper)).toContain('width: 0%')
  })

  it('进度比总集数还大(手输能造成)时截到 100%, 不溢出容器', () => {
    const wrapper = mount(ProgressBar, { props: { value: 99, total: 12 } })
    expect(fillWidth(wrapper)).toContain('width: 100%')
  })
})
