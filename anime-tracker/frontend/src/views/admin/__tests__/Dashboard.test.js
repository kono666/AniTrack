import { describe, it, expect, beforeEach, vi, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'

// 只给 Dashboard 真正 import 的那一个 —— 这个文件不是 adminLoadError.test.js 那种
// "一个 mock 喂三个页面"的写法, 所以不需要把那一长串导出抄过来.
vi.mock('../../../api', () => ({ getDashboard: vi.fn() }))

import Dashboard from '../Dashboard.vue'
import { getDashboard } from '../../../api'

/**
 * 看板上这次新加的两块: 时间维度(近 7/30 天新增)与真活跃度(日活/周活/近 14 天曲线).
 *
 * <p>这里钉的是**这几块在什么情况下画什么** —— 尤其是"一条事件都没有"时不能再画一条
 * 贴地的平线. 数算得对不对不归它管(那是后端 LoginEventIntegrationTest 的事, mock 看不见 SQL).
 */

const router = createRouter({
  history: createMemoryHistory(),
  routes: [{ path: '/', component: { template: '<div />' } }],
})

/** 后端固定回 14 个点, 日期由它算; 这里只要形状对 */
const FIRST_DAY = '2026-09-19'
const DAYS = 14

function trend(users) {
  return users.map((n, i) => {
    const d = new Date(Date.UTC(2026, 8, 19) + i * 86400000)
    return { date: d.toISOString().slice(0, 10), users: n }
  })
}

const ZEROS = new Array(DAYS).fill(0)

const LEGACY = {
  totalUsers: 128, activeUsers: 120, disabledUsers: 8,
  adminUsers: 2, totalTrackings: 3456, totalReviews: 789,
}

/**
 * 六个数都是挑过的: 两两不同, 也和新加的那些不同 —— 断言数字时若撞了值,
 * "这块没渲染"会因为它恰好和别处一样而变绿.
 */
const GROWTH = {
  last7d: { users: 11, reviews: 12, trackings: 13 },
  last30d: { users: 21, reviews: 22, trackings: 23 },
}

function payload(data) {
  return { data: { code: 200, data } }
}

function activity(users = ZEROS, trackedSince = FIRST_DAY) {
  return { dau: 3, wau: 9, failedAttempts24h: 12, trackedSince, trend: trend(users) }
}

/**
 * 渲染期抛出的异常**不会**让 mount 失败。
 *
 * <p>它走 Vue 的 errorHandler(默认是 warn + 重抛), 重抛那一下落在"未处理的 Promise"
 * 上 —— 用例照绿, vitest 只在边上打一行 `Errors 1 error`。反向验证时踩到了: 抽掉
 * `v-if="dashboard.growth"` 之后整页渲染当场炸掉, 这个文件 11 条全绿。
 *
 * <p>所以显式接住它, 并在每个用例结束后断言"一条都没有"。这比在某一两条用例里
 * 补一句断言更值: 它管的是**这个组件在任何一种入参下都不许渲染期抛异常**,
 * 包括以后新加的那些用例。
 */
const renderErrors = []

async function mountWith(data) {
  getDashboard.mockResolvedValue(payload(data))
  const wrapper = mount(Dashboard, {
    global: {
      plugins: [router],
      config: { errorHandler: e => renderErrors.push(e) },
    },
  })
  await flushPromises()
  return wrapper
}

/** 每根柱子的内联高度 */
function barHeights(wrapper) {
  return wrapper.findAll('.trend-bar').map(b => b.element.style.height)
}

beforeEach(() => {
  vi.clearAllMocks()
  renderErrors.length = 0
})

afterEach(() => {
  vi.restoreAllMocks()
  expect(renderErrors).toEqual([])
})

describe('看板的累计值(没动过的那一组)', () => {
  it('六张卡还是那六个键, 一个不少', async () => {
    const wrapper = await mountWith({ ...LEGACY, growth: GROWTH, activity: activity() })

    // 文档里的第一个 .stats-row 就是这六张. 下面两块各有一组 .stats-row, 所以按类查全部
    // 会把它们一起捞进来 —— 那条断言会永远成立, 等于没断言.
    const nums = wrapper.findAll('.stats-row')[0].findAll('.stat-num').map(n => n.text())

    expect(nums).toEqual(['128', '120', '8', '2', '3456', '789'])
  })
})

describe('时间维度', () => {
  it('近 7 天与近 30 天各一张卡, 三个增量各自显示', async () => {
    const wrapper = await mountWith({ ...LEGACY, growth: GROWTH, activity: activity() })

    const cards = wrapper.findAll('.stat-card--wide')
    expect(cards).toHaveLength(2)
    expect(cards[0].text()).toContain('近 7 天新增')
    expect(cards[1].text()).toContain('近 30 天新增')

    // 三个数必须分别落到自己的标签上. 只断言"页面里有 11"的话, 一个把 reviews 也读成
    // users 的实现照样能过 —— 而这正是这类卡片最容易抄错的地方.
    expect(cards[0].findAll('.stat-deltas span').map(s => s.text()))
      .toEqual(['11用户', '12短评', '13追番'])
    expect(cards[1].findAll('.stat-deltas span').map(s => s.text()))
      .toEqual(['21用户', '22短评', '23追番'])
  })

  it('后端没返回 growth 时这一块整个不渲染, 页面照常', async () => {
    const wrapper = await mountWith({ ...LEGACY })

    expect(wrapper.findAll('.stat-card--wide')).toHaveLength(0)
    expect(wrapper.text()).not.toContain('近 7 天新增')
  })
})

describe('活跃度', () => {
  it('今日 / 近 7 天 / 近 24 小时失败配的是三个键', async () => {
    const wrapper = await mountWith({ ...LEGACY, growth: GROWTH, activity: activity() })

    const panel = wrapper.find('.panel')
    expect(panel.exists()).toBe(true)
    expect(panel.findAll('.stat-num').map(n => n.text())).toEqual(['3', '9', '12'])
    expect(panel.text()).toContain('今日活跃')
    expect(panel.text()).toContain('近 7 天活跃')
    expect(panel.text()).toContain('近 24 小时登录失败')
  })

  it('柱子按窗口内峰值归一化; 只来了一个人的那天仍然画得出来', async () => {
    // 11 个 0, 然后 10 / 50 / 1 —— 峰值 50.
    // 最后那一天是这条用例的重点: 1/50 算出来是 2%, 不兜底的话它和 0 人长得一样,
    // 而"今天来了 1 个人"和"今天一个人都没来"是两件完全不同的事.
    const users = [...new Array(11).fill(0), 10, 50, 1]
    const wrapper = await mountWith({ ...LEGACY, growth: GROWTH, activity: activity(users) })

    expect(wrapper.findAll('.trend-bar')).toHaveLength(DAYS)
    expect(barHeights(wrapper)).toEqual([
      ...new Array(11).fill('0%'), '20%', '100%', '8%',
    ])
  })

  it('轴上标出窗口两端与峰值 —— 归一化之后高度不能跨时间比, 得有个绝对数兜底', async () => {
    const wrapper = await mountWith({
      ...LEGACY, growth: GROWTH, activity: activity([...new Array(13).fill(0), 7]),
    })

    const axis = wrapper.find('.trend-axis').text()
    expect(axis).toContain(FIRST_DAY)
    expect(axis).toContain('2026-10-02')   // 第 14 天
    expect(axis).toContain('7')
  })
})

describe('还没有数据的时候', () => {
  it('一条事件都没有时说的是「数据不足」, 而不是画一条贴地的平线', async () => {
    const wrapper = await mountWith({
      ...LEGACY, growth: GROWTH, activity: activity(ZEROS, null),
    })

    expect(wrapper.text()).toContain('数据不足')
    // 这一条才是重点: 一个"照常画 14 根 0 高柱子"的实现, 文案那半边照样能过.
    // 而它画出来的图和"这两周真没人来"一模一样, 读图的人一定会读错.
    expect(wrapper.findAll('.trend-bar')).toHaveLength(0)
  })

  it('记录起点晚于窗口起点时解释那几天的 0 是怎么来的', async () => {
    const wrapper = await mountWith({
      ...LEGACY, growth: GROWTH,
      activity: activity([...new Array(10).fill(0), 2, 3, 1, 4], '2026-09-29'),
    })

    // 窗口从 09-19 开始, 而记录从 09-29 才开始 —— 前 10 天的 0 不是"没人来"
    expect(wrapper.text()).toContain('2026-09-29')
    expect(wrapper.text()).toContain('不代表当时没人来')
  })

  it('记录起点就是窗口起点时不多这句解释(它不是常态文案)', async () => {
    const wrapper = await mountWith({ ...LEGACY, growth: GROWTH, activity: activity() })

    expect(wrapper.find('.trend-note').exists()).toBe(false)
  })

  it('后端没返回 activity 时这一块整个不渲染, 页面照常', async () => {
    const wrapper = await mountWith({ ...LEGACY, growth: GROWTH })

    expect(wrapper.find('.panel').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('今日活跃')
  })

  /**
   * 老版本后端 / 接口形状变了时, dashboard 不是一个带这些键的对象.
   *
   * <p>adminLoadError.test.js 里仪表盘的成功响应就是 `data: []` —— 少了模板里那些
   * `v-if` 与 `?.`, 那个文件会因为"读 undefined 的属性"整片变红, 而它验的根本
   * 不是这件事. 所以这条既是本页的兜底, 也是给那个文件留的一条线.
   */
  it('dashboard 不是预期形状时两块都不渲染, 也不抛', async () => {
    const wrapper = await mountWith([])

    expect(wrapper.find('.panel').exists()).toBe(false)
    expect(wrapper.findAll('.stat-card--wide')).toHaveLength(0)
    expect(wrapper.text()).not.toContain('数据不足')
  })
})
