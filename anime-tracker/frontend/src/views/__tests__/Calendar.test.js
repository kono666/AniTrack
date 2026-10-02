import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'

// 工厂整体替换 ../../api —— Calendar 只 import 了 getCalendar 这一个, 就只给这一个.
// ⚠️ 以后 Calendar 再 import 一个导出, 必须在这里补一行; 少了它会拿到 undefined,
// 而那个 TypeError 是在 load() 的 try 里抛的, 表现是"页面显示加载失败"而不是
// "测试报错", 很容易被读成"接口挂了".
vi.mock('../../api', () => ({
  getCalendar: vi.fn(() => Promise.resolve({ data: { data: [] } })),
}))

import Calendar from '../Calendar.vue'
import { getCalendar } from '../../api'

/**
 * 整周放送表.
 *
 * 这一页做出来是因为后端那条日历接口**一直**返回七天, 而此前只有首页 find 出今天
 * 那一格、其余六天直接丢掉. 所以这一页有三件"不摆出来就会悄悄坏掉"的事:
 *
 *   一、**七格恒定**. 按接口返回的数组遍历也能渲染出东西, 但那样"哪天缺了"与
 *       "那天没排片"在页面上长得一样(都是少一块) —— 用户看不出是哪个, 而我们会
 *       以为接口一直是完整的.
 *   二、**「今天」是文字**. 靠颜色标今天的话, 深色/浅色两套主题各是一套色差,
 *       对读屏用户则完全不存在. 这里断言的是那段文本本身.
 *   三、**失败要说出来**. 加载失败时渲染七个空格子, 看上去就是"这一周什么都没播" ——
 *       与真的没排片一模一样, 而这正是本站反复在防的那类错(把"没取到"读成"就是这样").
 *
 * 日期钉死在星期三, 不跟着运行那天跑(与 Home.today.test.js 同一个理由).
 */

const WED = 3
const ITEM_A = { id: 601, nameCn: '周三番', name: 'Wed', images: { medium: 'a.jpg' } }
const ITEM_B = { id: 602, nameCn: '周一番', name: 'Mon', images: { medium: 'b.jpg' } }

/** 真接口那一格的形状. id 是**字符串** —— 后端声明成 Map<String,String>,
 *  Jackson 会把 JSON 里的数字强制转成字符串, 照真实形状写, 别写数字 */
function day(id, items) {
  return { weekday: { en: 'X', cn: `星期${'日一二三四五六'[id === 7 ? 0 : id]}`, ja: 'X', id: String(id) }, items }
}

const router = createRouter({
  history: createMemoryHistory(),
  routes: [
    { path: '/calendar', component: { template: '<div />' } },
    { path: '/anime/:id', component: { template: '<div />' } },
  ],
})

function mountCalendar() {
  return mount(Calendar, { global: { plugins: [router] } })
}

describe('整周放送表', () => {
  let getDaySpy
  const mounted = []

  beforeEach(async () => {
    vi.clearAllMocks()
    getDaySpy = vi.spyOn(Date.prototype, 'getDay').mockReturnValue(WED)
    getCalendar.mockResolvedValue({ data: { data: [] } })
    await router.push('/calendar')
    await router.isReady()
  })

  afterEach(() => {
    mounted.forEach(w => w.unmount())
    mounted.length = 0
    getDaySpy.mockRestore()
  })

  function mountAt() {
    const w = mountCalendar()
    mounted.push(w)
    return w
  }

  it('恒定渲染七格 —— 接口只回一天时也是七格', async () => {
    // 接口回得**不完整**正是这条要防的: 按返回的数组遍历就只会画出一格,
    // 而页面上"少六格"与"那六天没排片"长得一样
    getCalendar.mockResolvedValue({ data: { data: [day(WED, [ITEM_A])] } })
    const w = mountAt()
    await flushPromises()

    expect(w.findAll('.cal-day')).toHaveLength(7)
    // 而且顺序是周一→周日, 不是接口给的顺序.
    // 先去掉空白再比: 模板里 `{{ wd.cn }}` 与那个 span 之间的换行会进 textContent,
    // 按原样比会把"模板怎么换行"也钉进去 —— 那不是这条断言想守的东西.
    expect(w.findAll('.cal-day-hd').map(h => h.text().replace(/\s+/g, ''))).toEqual([
      '星期一', '星期二', '星期三今天', '星期四', '星期五', '星期六', '星期日',
    ])
  })

  it('只有今天那一格带「今天」这两个字', async () => {
    getCalendar.mockResolvedValue({ data: { data: [] } })
    const w = mountAt()
    await flushPromises()

    const marks = w.findAll('.cal-today')
    expect(marks).toHaveLength(1)
    expect(marks[0].text()).toBe('今天')
    // 它在星期三那一格里
    expect(marks[0].element.closest('.cal-day').textContent).toContain('星期三')
  })

  it('片子落在它自己那一天, 不会串格', async () => {
    getCalendar.mockResolvedValue({
      data: { data: [day(1, [ITEM_B]), day(WED, [ITEM_A])] },
    })
    const w = mountAt()
    await flushPromises()

    const cells = w.findAll('.cal-day')
    // 索引 0 = 星期一, 索引 2 = 星期三(七格是固定顺序)
    expect(cells[0].text()).toContain('周一番')
    expect(cells[0].text()).not.toContain('周三番')
    expect(cells[2].text()).toContain('周三番')
    expect(cells[2].text()).not.toContain('周一番')
  })

  it('某天没有排片时明说「暂无排片」, 不是留一个空盒子', async () => {
    getCalendar.mockResolvedValue({ data: { data: [day(WED, [ITEM_A])] } })
    const w = mountAt()
    await flushPromises()

    // 六天没有 -> 六条提示; 有片那天没有
    expect(w.findAll('.cal-empty')).toHaveLength(6)
    const wed = w.findAll('.cal-day')[2]
    expect(wed.find('.cal-empty').exists()).toBe(false)
    expect(wed.find('.cal-card').exists()).toBe(true)
  })

  it('加载失败时整页走错误态, 不渲染七个空格子', async () => {
    getCalendar.mockRejectedValue(new Error('boom'))
    const w = mountAt()
    await flushPromises()

    expect(w.findAll('.cal-day')).toHaveLength(0)
    expect(w.find('.empty-state').exists()).toBe(true)
    // 错误态要有一个能重来的出口 —— 转瞬的失败不该要求用户刷新整页
    expect(w.text()).toContain('重试')
  })

  it('点「重试」会重新拉一次', async () => {
    getCalendar.mockRejectedValueOnce(new Error('boom'))
    const w = mountAt()
    await flushPromises()
    expect(getCalendar).toHaveBeenCalledTimes(1)

    getCalendar.mockResolvedValue({ data: { data: [day(WED, [ITEM_A])] } })
    await w.find('.action-btn').trigger('click')
    await flushPromises()

    expect(getCalendar).toHaveBeenCalledTimes(2)
    expect(w.findAll('.cal-day')).toHaveLength(7)
    expect(w.text()).toContain('周三番')
  })

  it('卡片是键盘可达的, 回车与空格都能进详情', async () => {
    getCalendar.mockResolvedValue({ data: { data: [day(WED, [ITEM_A])] } })
    const w = mountAt()
    await flushPromises()

    const card = w.find('.cal-card')
    // 只有 @click 的 div 键盘到不了、读屏也不说它能按 —— role=button 的约定是
    // 回车与空格**两个**都要触发, 少一个就是"看起来能按, 按了没反应"
    expect(card.attributes('role')).toBe('button')
    expect(card.attributes('tabindex')).toBe('0')

    const push = vi.spyOn(router, 'push')
    await card.trigger('keydown.enter')
    expect(push).toHaveBeenCalledWith('/anime/601')
    await card.trigger('keydown.space')
    expect(push).toHaveBeenCalledTimes(2)
    push.mockRestore()
  })

  it('点卡片进详情页', async () => {
    getCalendar.mockResolvedValue({ data: { data: [day(WED, [ITEM_A])] } })
    const w = mountAt()
    await flushPromises()

    const push = vi.spyOn(router, 'push')
    await w.find('.cal-card').trigger('click')
    expect(push).toHaveBeenCalledWith('/anime/601')
    push.mockRestore()
  })

  it('没有 nameCn 的条目退回原名, 不显示空标题', async () => {
    getCalendar.mockResolvedValue({
      data: { data: [day(WED, [{ id: 603, name: 'Raw Name', images: {} }])] },
    })
    const w = mountAt()
    await flushPromises()

    expect(w.find('.cal-name').text()).toBe('Raw Name')
  })
})
