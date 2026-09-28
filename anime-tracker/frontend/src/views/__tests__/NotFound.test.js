import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import router from '../../router'
import NotFound from '../NotFound.vue'

/**
 * 404 兜底路由.
 *
 * 改前路由表里没有兜底条目, 于是访问一个不存在的地址时 router 匹配不到任何东西,
 * 页面全白 —— 没有错误, 没有提示, 控制台也安安静静. 这类访问在这个站上不算罕见:
 * 有人就是会去戳地址栏, 或者点开一个早就失效的分享链接.
 *
 * 用真实的路由实例(而不是自己搭一个 routes 数组)来测: 要验的正是**真正生效的那张
 * 路由表**, 包括兜底条目必须排在最后这件事.
 */
describe('404 兜底路由', () => {
  beforeEach(() => {
    localStorage.clear()
  })

  it('单级未知路径落到 404', async () => {
    await router.push('/nope')
    expect(router.currentRoute.value.name).toBe('NotFound')
  })

  it('多级未知路径也落到 404', async () => {
    // 这条钉的是「兜底必须是个能吃下 / 的正则参数」.
    //
    // 反例不是尾部少一个星号, 而是写成普通的 :pathMatch —— 普通参数只匹配**一级**,
    // 于是 /a/b/c 依然落到白屏(而 /nope 是好的, 所以只有这条用例能发现).
    //
    // 顺带记一个容易想当然的点: `:pathMatch(.*)*` 尾部那个星号**不影响匹配范围**
    // (实测把星号去掉, 全部用例照样绿). 它只决定 params.pathMatch 解析成数组还是
    // 字符串 —— 而本页读的是 route.fullPath, 用不到这个参数, 所以这里不为它设断言.
    await router.push('/a/b/c')
    expect(router.currentRoute.value.name).toBe('NotFound')
  })

  it('已知路径不受兜底条目影响', async () => {
    // 反过来钉一次: 兜底条目要是放到了路由表最前面, 它会把所有路径(包括正常的
    // 页面)全吃下去, 那是比白屏更糟的一种坏法
    await router.push('/search')
    expect(router.currentRoute.value.name).toBe('Search')
  })

  it('404 页面回显访问的地址, 并能回首页', async () => {
    await router.push('/anime/undefined/missing')
    const wrapper = mount(NotFound, { global: { plugins: [router] } })

    // 把用户实际访问的路径显示出来, 他才知道是哪里不对
    expect(wrapper.text()).toContain('/anime/undefined/missing')
    expect(wrapper.text()).toContain('没有这个页面')

    await wrapper.find('button').trigger('click')

    // push 之后还要等路由把组件取回来(路由是懒加载的), 所以用 waitFor 而不是
    // flushPromises: 后者只清微任务, 等不到动态 import 完成
    await vi.waitFor(() => {
      expect(router.currentRoute.value.path).toBe('/')
    })
  })
})
