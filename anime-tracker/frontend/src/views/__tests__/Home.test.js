import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'

vi.mock('../../api', () => ({
  getRanking: vi.fn(() => Promise.resolve({ data: { data: [] } })),
  getCalendar: vi.fn(() => Promise.resolve({ data: { data: [] } })),
  getTags: vi.fn(() => Promise.resolve({ data: { data: [] } })),
  // 分类浏览改走 /filter(它返回 {list,total,page}, 有真实 total 才谈得上翻页)
  getFiltered: vi.fn(() => Promise.resolve({ data: { data: { list: [], total: 0 } } })),
}))

import Home from '../Home.vue'
import { getRanking, getCalendar, getTags, getFiltered } from '../../api'
import { resetHomeCache } from '../../utils/homeCache'

/**
 * 首页缓存的 TTL 只有在缓存对象**活得比组件实例久**的时候才谈得上生效.
 *
 * 所以这里不是断言"缓存了", 而是断言"卸载再挂载之后不再发请求" —— 改前缓存
 * 写在 <script setup> 里, 每个实例一份, 卸载即丢, 这个用例会红.
 */

const router = createRouter({
  history: createMemoryHistory(),
  routes: [
    { path: '/', component: { template: '<div />' } },
    { path: '/anime/:id', component: { template: '<div />' } },
  ],
})

// Home 用 v-reveal (IntersectionObserver), jsdom 没有实现它
class FakeIntersectionObserver {
  observe() {}
  unobserve() {}
  disconnect() {}
}

function mountHome() {
  return mount(Home, { global: { plugins: [router] } })
}

describe('首页缓存', () => {
  beforeEach(() => {
    resetHomeCache()
    vi.clearAllMocks()
    vi.stubGlobal('IntersectionObserver', FakeIntersectionObserver)
    getRanking.mockResolvedValue({ data: { data: [] } })
  })

  it('第二次进入首页直接用缓存, 不再发请求', async () => {
    const first = mountHome()
    await flushPromises()
    // 核心数据是两个排行接口(rank + date)
    expect(getRanking).toHaveBeenCalledTimes(2)
    first.unmount()

    const second = mountHome()
    await flushPromises()

    expect(getRanking).toHaveBeenCalledTimes(2)
    second.unmount()
  })

  it('过了 5 分钟 TTL 就重新请求', async () => {
    // 只伪造 Date, 不伪造定时器 —— flushPromises 自己要用定时器
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.setSystemTime(new Date('2026-09-29T10:00:00Z'))

    const first = mountHome()
    await flushPromises()
    first.unmount()
    expect(getRanking).toHaveBeenCalledTimes(2)

    vi.setSystemTime(new Date('2026-09-29T10:05:01Z'))
    const second = mountHome()
    await flushPromises()

    expect(getRanking).toHaveBeenCalledTimes(4)
    second.unmount()
    vi.useRealTimers()
  })

  it('加载失败不写缓存: 再进来会真的重发请求, 并显示错误态', async () => {
    getRanking.mockRejectedValueOnce(new Error('boom'))

    const first = mountHome()
    await flushPromises()

    expect(first.text()).toContain('加载首页失败')
    first.unmount()

    const second = mountHome()
    await flushPromises()

    // 失败的那次没写缓存, 所以还会有新请求发出去(而不是命中一份空缓存)
    expect(getRanking.mock.calls.length).toBeGreaterThan(2)
    second.unmount()
  })
})

/**
 * 首页三类入口的键盘可达性.
 *
 * 改前 today-card / hs-card / tag-chip 全都只有 @click: 它们是 div 和 span,
 * tab 键直接跳过去, 读屏软件也不说这是能按的东西. 鼠标用户永远看不出这个
 * 问题, 所以只能靠断言把 role / tabindex / 按键这三件事钉住.
 *
 * 题外话: interactions.css 里那份 :focus-visible 名单**早就**把 .anime-card、
 * .tag-chip 这些类写进去了 —— 也就是说当初是打算给它们做焦点态的,
 * 只是一直没有元素能被 focus, 那条规则从写下那天起就没匹配过任何东西.
 */

const TODAY_ITEM = { id: 501, nameCn: '今日番', name: 'Today', images: { medium: 'a.jpg' } }
const HS_ITEM = { id: 502, nameCn: '热门番', name: 'Hot', images: { large: 'b.jpg' } }

/**
 * 日历假数据必须**独立于被测代码**推导.
 *
 * 改前这里是 `WEEKDAYS[new Date().getDay()]`, 而 WEEKDAYS 就是 Home.vue 里那个
 * 数组 —— 断言与被测代码共用同一个常量, 于是把两边一起写错. 真接口回的是
 * `{cn:'星期三', id:3}`, 假数据回的是 `{cn:'周三'}`, 在"匹配得上"这个意义上
 * 长得一模一样, 所以「今日放送」整个区块消失了一整轮, 测试却全绿.
 *
 * 现在按真接口的形状独立写一遍: cn 是「星期X」(不是「周X」), id 是 1(周一)
 * 到 7(周日) 的**字符串**(后端把它收成了 Map<String,String>, Jackson 会把 JSON
 * 里的数字强制转成字符串).
 */
const CN_DIGIT = ['日', '一', '二', '三', '四', '五', '六']
const EN_DOW = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat']
function todayCalendarDay(items) {
  const dow = new Date().getDay()          // 0=周日 .. 6=周六
  const id = dow === 0 ? 7 : dow           // Bangumi: 1=周一 .. 7=周日
  return { weekday: { en: EN_DOW[dow], cn: `星期${CN_DIGIT[dow]}`, ja: 'x', id: String(id) }, items }
}

/** 日历是按"今天星期几"取的, 所以假数据也得挂在今天那一格上 */
function calendarForToday(items) {
  return { data: { data: [todayCalendarDay(items)] } }
}

describe('首页卡片的键盘操作', () => {
  beforeEach(async () => {
    resetHomeCache()
    vi.clearAllMocks()
    vi.stubGlobal('IntersectionObserver', FakeIntersectionObserver)
    getRanking.mockResolvedValue({ data: { data: [HS_ITEM] } })
    getCalendar.mockResolvedValue(calendarForToday([TODAY_ITEM]))
    getTags.mockResolvedValue({ data: { data: [{ name: '治愈', count: 3 }] } })
    getFiltered.mockResolvedValue({ data: { data: { list: [], total: 0 } } })

    await router.push('/')
    await router.isReady()
  })

  it('今日放送: 回车和空格都能打开详情', async () => {
    const wrapper = mountHome()
    await flushPromises()

    const card = wrapper.find('.today-card')
    expect(card.attributes('role')).toBe('button')
    expect(card.attributes('tabindex')).toBe('0')

    await card.trigger('keydown.enter')
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/anime/501')

    // role=button 的约定是回车和空格都触发. 只测回车的话,
    // "按空格没反应"会成为一个只有键盘用户才会撞上的 bug
    await router.push('/')
    await wrapper.find('.today-card').trigger('keydown.space')
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/anime/501')
  })

  it('横向滚动区的卡片同样是可选中的按钮', async () => {
    const wrapper = mountHome()
    await flushPromises()

    const card = wrapper.find('.hs-card')
    expect(card.attributes('role')).toBe('button')
    expect(card.attributes('tabindex')).toBe('0')

    await card.trigger('keydown.enter')
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/anime/502')
  })

  it('分类标签能选中, 回车即切换分类', async () => {
    const wrapper = mountHome()
    await flushPromises()

    const chips = wrapper.findAll('.tag-chip')
    expect(chips[0].text()).toBe('全部')
    expect(chips[0].attributes('tabindex')).toBe('0')
    expect(chips[1].attributes('role')).toBe('button')

    await chips[1].trigger('keydown.enter')
    await flushPromises()
    // 回车要真的等于点了一下, 而不只是把焦点停在那儿
    expect(getFiltered).toHaveBeenCalledWith(expect.objectContaining({ tag: '治愈', page: 1 }))
  })

  it('按空格不会把页面往下滚(默认行为被拦掉了)', async () => {
    const wrapper = mountHome()
    await flushPromises()

    const event = new KeyboardEvent('keydown', { key: ' ', bubbles: true, cancelable: true })
    wrapper.find('.today-card').element.dispatchEvent(event)

    expect(event.defaultPrevented).toBe(true)
  })
})
