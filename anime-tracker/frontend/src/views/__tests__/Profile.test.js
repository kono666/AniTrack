import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createMemoryHistory } from 'vue-router'

vi.mock('../../api', () => ({
  getTrackingList: vi.fn(),
  getOverallStats: vi.fn(),
  saveTracking: vi.fn(),
  // 通知那一块也是这一页会调的接口 —— 整体替换模块, 漏一个就在解构时抛
  getNotifications: vi.fn(),
  markNotificationsRead: vi.fn(),
}))

import Profile from '../Profile.vue'
import { getTrackingList, getOverallStats, saveTracking, getNotifications } from '../../api'

/**
 * 个人页的两个数字口径问题.
 *
 * 一、「在看」原先算的是 totalAnime - completed, 也就是"除了看完的都算在看":
 *     想看、搁置、抛弃全被算了进去. 而同一页下面的筛选栏就摆着一个
 *     「在看 N」—— 两个数字经常对不上, 用户没有理由知道该信哪个.
 *
 * 二、「+1 集」原先没有上限: 12 集的番能被点到 13/12. 进度条按百分比卡在 100%
 *     所以看不出来, 但数字就写在那儿, 而且会进库、进统计.
 */

const router = createRouter({
  history: createMemoryHistory(),
  routes: [
    { path: '/', component: { template: '<div />' } },
    { path: '/anime/:id', component: { template: '<div />' } },
    { path: '/login', component: { template: '<div />' } },
  ],
})

const TRACKINGS = [
  { id: 1, subjectId: 101, animeTitle: 'A', status: 'watching', progress: 3, totalEpisodes: 12, score: 8 },
  { id: 2, subjectId: 102, animeTitle: 'B', status: 'watching', progress: 12, totalEpisodes: 12, score: 7 },
  { id: 3, subjectId: 103, animeTitle: 'C', status: 'want_to_watch', progress: 0, totalEpisodes: 24 },
  { id: 4, subjectId: 104, animeTitle: 'D', status: 'on_hold', progress: 2, totalEpisodes: 24 },
  { id: 5, subjectId: 105, animeTitle: 'E', status: 'dropped', progress: 1, totalEpisodes: 12 },
  { id: 6, subjectId: 106, animeTitle: 'F', status: 'watched', progress: 12, totalEpisodes: 12 },
]

/** 按标题取那一行的 +1 按钮 */
function plusOneOf(wrapper, title) {
  const card = wrapper.findAll('.p-card').find(c => c.text().includes(title))
  return card.find('.pca-btn')
}

async function mountProfile() {
  localStorage.setItem('anime_user', JSON.stringify({ id: 1, username: 'alice', token: 'jwt', role: 'USER' }))
  setActivePinia(createPinia())
  await router.push('/')
  await router.isReady()

  const wrapper = mount(Profile, { global: { plugins: [router] } })
  await flushPromises()
  return wrapper
}

/**
 * 这一页会调的接口给一套默认值.
 *
 * vi.mock('../../api', ...) 是**整体替换**, 没给实现的 mock 返回 undefined,
 * 而 loadProfile 里是解构 .data —— 当场抛, 整页落到错误态, 后面所有断言都会变成
 * "找不到元素". 所以每个 describe 的 beforeEach 都得先铺这一层.
 */
function stubApis() {
  getTrackingList.mockResolvedValue({ data: { code: 200, data: TRACKINGS.map(t => ({ ...t })) } })
  getOverallStats.mockResolvedValue({ data: { code: 200, data: { totalAnime: 6, totalEpisodes: 30, totalReviews: 2, avgScore: 7.5, completed: 1 } } })
  saveTracking.mockResolvedValue({ data: { code: 200, data: { id: 1 } } })
  // 通知那一块默认给空 —— 那几个用例测的是追番统计, 不关心它.
  // 不给也能跑(那一块的失败自己咽掉了, 只显示"通知暂时拉不到"), 但给空更接近
  // 真实情况: 那些用例的断言是"整页正常", 而一个必然失败的附带区块混在里面,
  // 会把"整页正常"这件事的成色说糊
  getNotifications.mockResolvedValue({ data: { code: 200, data: { list: [], total: 0 } } })
}

/** 按标题取那一行的状态下拉 */
function statusSelectOf(wrapper, title) {
  const card = wrapper.findAll('.p-card').find(c => c.text().includes(title))
  return card.find('.pca-select')
}

describe('个人页的统计口径与 +1 封顶', () => {
  beforeEach(() => {
    localStorage.clear()
    vi.clearAllMocks()
    stubApis()
  })

  it('「在看」按 status=watching 统计, 与筛选栏同一口径', async () => {
    const wrapper = await mountProfile()

    const stats = wrapper.findAll('.p-stat')
    const watching = stats.find(s => s.text().includes('在看'))
    // 6 条记录里只有 2 条是 watching. 改前那个算法(6-1)会给出 5
    expect(watching.find('.ps-num').text()).toBe('2')

    // 与筛选栏上的「在看」计数一致 —— 这正是改前对不上的那两个数
    const tab = wrapper.findAll('.p-tab').find(t => t.text().startsWith('在看'))
    expect(tab.text()).toContain('2')
  })

  it('未看到最后一集时 +1 提交 progress+1', async () => {
    const wrapper = await mountProfile()
    await plusOneOf(wrapper, 'A').trigger('click')
    await flushPromises()

    expect(saveTracking).toHaveBeenCalledWith(expect.objectContaining({ subjectId: 101, progress: 4 }))
  })

  it('已经看完的番, +1 按钮被禁用', async () => {
    const wrapper = await mountProfile()
    expect(plusOneOf(wrapper, 'B').attributes('disabled')).toBeDefined()
  })

  it('点不动的按钮不发请求, 也不会把 12 变成 13', async () => {
    const wrapper = await mountProfile()
    await plusOneOf(wrapper, 'B').trigger('click')
    await flushPromises()

    expect(saveTracking).not.toHaveBeenCalled()
  })

  it('总集数未知时不封顶也不禁用(那种情况下任何上限都是编的)', async () => {
    getTrackingList.mockResolvedValue({
      data: { code: 200, data: [{ id: 9, subjectId: 109, animeTitle: '未知集数', status: 'watching', progress: 99 }] },
    })
    const wrapper = await mountProfile()

    expect(plusOneOf(wrapper, '未知集数').attributes('disabled')).toBeUndefined()
    await plusOneOf(wrapper, '未知集数').trigger('click')
    await flushPromises()
    expect(saveTracking).toHaveBeenCalledWith(expect.objectContaining({ progress: 100 }))
  })

  /**
   * 声明总集数是 0 时, 进度条退到本地收齐的条数 —— 这条只改**显示**。
   *
   * 为什么需要: `total_episodes` 上游对绝大多数条目就是 0, 改前 `v-if="item.totalEpisodes"`
   * 恒假, 追番列表里九成九的行**根本没有进度条**。
   *
   * ⚠️ 同时钉住「`+1` 的闸门不跟着改」: 那一页的本地条数来自库里的旧数据、这一页不刷新,
   *    连载中的番只收到已播的 8 集时, 拿它禁用 `+1` 会把正常的「看下一集」挡掉。
   *    所以这里 `progress=3 < 12`, 按钮**必须仍然可点**; 若哪天有人把 atLastEpisode
   *    也改成读本地条数, 这条会红。
   */
  it('声明总集数是 0 时进度条退到本地条数, 但 +1 的闸门不动', async () => {
    getTrackingList.mockResolvedValue({
      data: { code: 200, data: [{ id: 8, subjectId: 108, animeTitle: '本地有条数', status: 'watching', progress: 3, totalEpisodes: 0, episodeTotal: 12 }] },
    })
    const wrapper = await mountProfile()

    const card = wrapper.findAll('.p-card').find(c => c.text().includes('本地有条数'))
    expect(card.find('.pc-progress').exists()).toBe(true)
    expect(card.find('.pc-prog-text').text()).toBe('3/12')
    expect(card.find('.pc-fill').attributes('style')).toContain('width: 25%')

    expect(plusOneOf(wrapper, '本地有条数').attributes('disabled')).toBeUndefined()
  })
})

/**
 * 改一个字段, 就只发一个字段.
 *
 * 改前这两处都是把**整行**发回去(含这一页进来时拉到的 progress / score).
 * 于是"本地那份副本"只要旧了一点点 —— 页面开着放了一会儿、去详情页打过卡再切回来、
 * 开了两个标签页 —— 改状态就会把进度写回旧值, 而界面上两处都显示成功.
 *
 * 断言写的是**精确对象**而不是 objectContaining: 多带一个字段正是这个 bug 本身,
 * 用 objectContaining 的话它永远绿.
 */
/**
 * 按钮上那三个字说清了吗.
 *
 * 改前按钮上只有「+1」, 用户问过一次「+1 啥意思」—— 它字面上答不出最要紧的
 * 那半句: 加的是**你的进度**, 不是番剧的集数. 现在按钮写「+1 集」, 悬停提示
 * 把结果用具体数字说出来(点完会变成第几集), 列表上方还有一句总说明.
 */
describe('「+1 集」这句文案', () => {
  beforeEach(() => {
    localStorage.clear()
    vi.clearAllMocks()
    stubApis()
  })

  it('按钮上写的是「+1 集」, 不是含混的「+1」', async () => {
    const wrapper = await mountProfile()

    expect(plusOneOf(wrapper, 'A').text()).toBe('+1 集')
  })

  it('提示说的是点完会变成第几集, 而不是重复一遍「加一集」', async () => {
    const wrapper = await mountProfile()

    // A 是 3/12
    expect(plusOneOf(wrapper, 'A').attributes('title')).toContain('第 4 集')
  })

  it('已经看到最后一集的番, 提示不承诺一件做不到的事', async () => {
    const wrapper = await mountProfile()

    // B 是 12/12, 按钮是禁用的 —— 禁用的按钮在浏览器里不弹 title, 但这条文案
    // 同时是 aria-label, 屏幕阅读器会读它(否则读出来只有一句「按钮, 不可用」)
    const hint = plusOneOf(wrapper, 'B').attributes('title')
    expect(hint).toContain('最后一集')
    expect(hint).not.toContain('第 13 集')
  })

  it('列表上方有一句总说明', async () => {
    const wrapper = await mountProfile()

    expect(wrapper.find('.p-hint').text()).toContain('进度往前推一格')
  })

  it('没有追番记录时不出这句说明(它说的是上面那个列表)', async () => {
    getTrackingList.mockResolvedValue({ data: { code: 200, data: [] } })
    const wrapper = await mountProfile()

    expect(wrapper.find('.p-hint').exists()).toBe(false)
  })
})

describe('个人页只提交动过的那个字段', () => {
  beforeEach(() => {
    localStorage.clear()
    vi.clearAllMocks()
    stubApis()
  })

  it('+1 只发 progress, 不带 status / score', async () => {
    const wrapper = await mountProfile()
    await plusOneOf(wrapper, 'A').trigger('click')
    await flushPromises()

    expect(saveTracking).toHaveBeenCalledWith({ subjectId: 101, progress: 4 })
  })

  it('改状态只发 status, 不把本地那份进度一起发回去', async () => {
    const wrapper = await mountProfile()
    await statusSelectOf(wrapper, 'A').setValue('watched')
    await flushPromises()

    expect(saveTracking).toHaveBeenCalledWith({ subjectId: 101, status: 'watched' })
  })

  it('改状态成功之后卡片上的徽章跟着变', async () => {
    const wrapper = await mountProfile()
    await statusSelectOf(wrapper, 'A').setValue('watched')
    await flushPromises()

    const card = wrapper.findAll('.p-card').find(c => c.text().includes('A'))
    expect(card.find('.pc-status-badge').text()).toBe('看过')
  })
})
