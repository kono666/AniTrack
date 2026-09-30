import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'

vi.mock('../../api', () => ({
  getRanking: vi.fn(() => Promise.resolve({ data: { data: [] } })),
  getCalendar: vi.fn(() => Promise.resolve({ data: { data: [] } })),
  getTags: vi.fn(() => Promise.resolve({ data: { data: [] } })),
  getFiltered: vi.fn(() => Promise.resolve({ data: { data: { list: [], total: 0 } } })),
}))

import Home from '../Home.vue'
import { getRanking, getCalendar } from '../../api'
import { resetHomeCache } from '../../utils/homeCache'

/**
 * 「今日放送」的星期比对.
 *
 * 改前 Home.vue 拿 `['周日','周一',…][new Date().getDay()]` 拼出 '周三' 去比日历
 * 接口回的 `weekday.cn` —— 而那个字段的真值是 '星期三'. 字符串对不上, find 永远
 * 返回 undefined, todayAnime 恒为空数组, 于是整个区块被 `v-if="todayAnime.length > 0"`
 * 藏掉了. 一个区块消失得悄无声息: 没有报错, 也没有任何测试会红(当时那份假数据是
 * 用被测代码**同一个** WEEKDAYS 常量拼的, 两边一起错, 反而"对得上")。
 *
 * 所以这个文件里:
 *   · 假数据按真接口的形状**静态**写死, 不复用被测代码的任何一个常量;
 *   · 日期钉死在星期三(getDay() → 3), 不跟着运行的那天跑.
 *
 * 单独一个文件而不是并进 Home.test.js: 与 Home.tagUrl.test.js 同一个理由 ——
 * 卸载不干净的 wrapper 会把它的 route watcher 留给后面的用例.
 */

const WED = 3
const TODAY_ITEM = { id: 501, nameCn: '今日番', name: 'Today', images: { medium: 'a.jpg' } }
const OTHER_ITEM = { id: 502, nameCn: '别的番', name: 'Other', images: { medium: 'b.jpg' } }

/** 真接口那一格的形状: {en:'Wed', cn:'星期三', ja:'水曜日', id:1} —— id 是**字符串**,
 *  因为后端声明成 Map<String,String>, Jackson 会把 JSON 里的数字强制转成字符串 */
function day(id, items) {
  return { weekday: { en: 'Wed', cn: `星期${'日一二三四五六'[id === 7 ? 0 : id]}`, ja: '水曜日', id: String(id) }, items }
}

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

describe('首页「今日放送」的星期比对', () => {
  let getDaySpy
  const mounted = []

  beforeEach(async () => {
    resetHomeCache()
    vi.clearAllMocks()
    vi.stubGlobal('IntersectionObserver', FakeIntersectionObserver)
    // 只假 getDay 这一个方法, 不动假定时器 —— 假定时器会把 flushPromises 里的
    // setTimeout 一起冻住, 测试直接挂死
    getDaySpy = vi.spyOn(Date.prototype, 'getDay').mockReturnValue(WED)
    getRanking.mockResolvedValue({ data: { data: [] } })
    await router.push('/')
    await router.isReady()
  })

  afterEach(() => {
    mounted.forEach(w => w.unmount())
    mounted.length = 0
    getDaySpy.mockRestore()
  })

  function mountAt() {
    const w = mountHome()
    mounted.push(w)
    return w
  }

  it('日历里今天那一格有片子时, 区块要显示出来', async () => {
    getCalendar.mockResolvedValue({ data: { data: [day(1, [OTHER_ITEM]), day(WED, [TODAY_ITEM])] } })
    const wrapper = mountAt()
    await flushPromises()

    // 这一条在改前是红的: 拿 '周三' 比 '星期三' 永远不中, 区块整个不渲染
    expect(wrapper.find('.today-card').exists()).toBe(true)
    expect(wrapper.text()).toContain('今日番')
    // 而且要是**今天那一格**的片子, 不是数组里第一个
    expect(wrapper.text()).not.toContain('别的番')
  })

  it('日历里今天那一格是空的时, 区块不显示(对照: 不是无条件渲染)', async () => {
    getCalendar.mockResolvedValue({ data: { data: [day(1, [OTHER_ITEM]), day(WED, [])] } })
    const wrapper = mountAt()
    await flushPromises()

    expect(wrapper.find('.today-card').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('今日放送')
  })

  it('id 是数字时同样匹配 —— 后端哪天把 weekday 换成强类型就用得上', async () => {
    // 现在走的是 Map<String,String> 那条路(拿到 "3"), 这一条钉的是 String() 归一:
    // 如果有人把 DTO 改成 Integer id, 不改这里就会静默回到"区块永远不显示"
    const d = day(WED, [TODAY_ITEM])
    d.weekday.id = WED   // 数字, 不是字符串
    getCalendar.mockResolvedValue({ data: { data: [d] } })
    const wrapper = mountAt()
    await flushPromises()

    expect(wrapper.find('.today-card').exists()).toBe(true)
  })
})
