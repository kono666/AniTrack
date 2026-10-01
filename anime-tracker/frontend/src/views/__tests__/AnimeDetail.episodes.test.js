import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createMemoryHistory } from 'vue-router'

// 打勾失败必须弹一句提示(改前是空 catch). 真实的 useToast 只往模块作用域的
// ref 里塞, 组件单独挂载时没有任何 DOM 能看到 —— 不打桩就断言不了「说出来了」,
// 而这一条正是 c98 从静默改成会提示的全部意义
const { toastSpy } = vi.hoisted(() => ({ toastSpy: vi.fn() }))
vi.mock('../../composables/useToast', () => ({
  useToast: () => ({ show: toastSpy, remove: vi.fn(), items: { value: [] } }),
  showToast: toastSpy,
}))

vi.mock('../../api', () => ({
  getAnimeDetail: vi.fn(),
  getEpisodes: vi.fn(),
  getRatingStats: vi.fn(),
  getSubjectReviews: vi.fn(),
  getMyReview: vi.fn(),
  saveReview: vi.fn(),
  deleteMyReview: vi.fn(),
  getTrackingStatus: vi.fn(),
  saveTracking: vi.fn(),
  deleteTracking: vi.fn(),
  getWatchedEpisodes: vi.fn(),
  toggleEpisode: vi.fn(),
  getAnimeHeat: vi.fn(),
  getFiltered: vi.fn(),
  // 点赞那三个, 加上两个排序常量 —— vi.mock 整体替换模块, 漏一个就在解构时抛,
  // 整页落到错误态(见这个文件顶部那条). 常量也得列: 漏了组件里就是 undefined
  likeReview: vi.fn(),
  unlikeReview: vi.fn(),
  getReviewLikers: vi.fn(),
  // 回复那一组同理, 一个都不能漏(整体替换模块)
  getReplies: vi.fn(),
  addReply: vi.fn(),
  editReply: vi.fn(),
  deleteReply: vi.fn(),
  likeReply: vi.fn(),
  unlikeReply: vi.fn(),
  getReplyLikers: vi.fn(),
  // 举报同理(值 + 那四个理由的常量), 理由也要列全 —— 漏了就是面板空白
  reportReview: vi.fn(),
  REVIEW_REPORT_REASONS: [
    { value: 'SPAM', label: '垃圾广告' },
    { value: 'ABUSE', label: '辱骂攻击' },
    { value: 'SPOILER', label: '剧透' },
    { value: 'OTHER', label: '其他' },
  ],
  REVIEW_SORT_CREATED: 'createdAt',
  REVIEW_SORT_HOT: 'hot',
}))

import AnimeDetail from '../AnimeDetail.vue'
import {
  getAnimeDetail, getEpisodes, getRatingStats, getSubjectReviews, getFiltered,
  getWatchedEpisodes, getAnimeHeat, getTrackingStatus, getMyReview, toggleEpisode,
} from '../../api'

/**
 * 剧集多的番不该跟普通番一个加载/渲染逻辑.
 *
 * 改前是 `v-for="ep in episodes"` —— 一次把**全部**剧集铺成 DOM. 海贼王那种
 * 一千多集的番点进去要卡一下: 1000 集 = 4000+ 个节点, 而每个 tile 还要做两次
 * O(n) 的 watchedEpisodes.includes(...), 点一个格子更卡. 而站内绝大多数是
 * 12/13/24/25/26 集的季番 —— 给它们加一个分页控件只是多一层要点的东西.
 * 所以是**超过阈值才分页**.
 *
 * 三个数在这里**硬编码**(100 阈值 / 50 一页), 不从组件 import: 断言与被测代码
 * 共用同一个常量的话, 常量改错了两边一起错, 测试照样绿(Home.test.js 里的
 * WEEKDAYS 就是这个教训, 那次坏了一整轮没人发现).
 *
 * ⚠️ 单独一个文件: 与 AnimeDetail.missing.test.js 同一个理由 —— 这里要造
 * 1000 条假数据并数 DOM, 混进既有两个文件(它们的 getEpisodes 一律返回空数组、
 * 从不渲染 tile)会互相污染.
 */

const router = createRouter({
  history: createMemoryHistory(),
  routes: [{ path: '/anime/:id', component: { template: '<div />' } }],
})

/** 造 n 集, 集号 1..n */
const eps = n => Array.from({ length: n }, (_, i) => ({
  id: 10000 + i, sort: i + 1, name: `Episode ${i + 1}`, nameCn: `第${i + 1}集`,
}))

async function mountWith(episodes, watched = [], tracking = { tracked: false }) {
  localStorage.setItem('anime_user', JSON.stringify({ id: 1, username: 'alice', token: 'jwt', role: 'USER' }))
  setActivePinia(createPinia())

  getAnimeDetail.mockResolvedValue({ data: { code: 200, data: { id: 7, nameCn: '长篇番', rating: {} } } })
  getEpisodes.mockResolvedValue({ data: { code: 200, data: episodes } })
  getRatingStats.mockResolvedValue({ data: { code: 200, data: { average: 0, count: 0, distribution: Array(10).fill(0) } } })
  getSubjectReviews.mockResolvedValue({ data: { code: 200, data: [] } })
  getFiltered.mockResolvedValue({ data: { code: 200, data: { list: [], total: 0 } } })
  getTrackingStatus.mockResolvedValue({ data: { code: 200, data: tracking } })
  getMyReview.mockResolvedValue({ data: { code: 200, data: { exists: false } } })
  getWatchedEpisodes.mockResolvedValue({ data: { code: 200, data: watched } })
  getAnimeHeat.mockResolvedValue({ data: { code: 200, data: null } })

  await router.push('/anime/7')
  await router.isReady()
  const wrapper = mount(AnimeDetail, { global: { plugins: [router] } })
  await flushPromises()
  return wrapper
}

/** DOM 里实际画出来的集号 */
const nums = w => w.findAll('.ep-tile-num').map(n => Number(n.text()))

/** 点分页控件上那个写着 page 的按钮 */
async function clickPage(w, page) {
  const btn = w.findAll('.pg-btn').find(b => b.text() === String(page))
  expect(btn, `分页控件上应当有第 ${page} 页的按钮`).toBeTruthy()
  await btn.trigger('click')
  await flushPromises()
}

describe('详情页剧集: 超过阈值才分页', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    // jsdom 里 Element.prototype.scrollIntoView 是 **undefined**(不是 no-op) ——
    // 不打桩的话翻页会直接 TypeError, 而它会被组件里那些 try/catch 吞掉,
    // 变成一个"看着绿其实什么都没发生"的假绿
    Element.prototype.scrollIntoView = vi.fn()
  })

  afterEach(() => {
    localStorage.clear()
    vi.restoreAllMocks()
  })

  it('12 集的季番: 没有分页控件, 12 个格子全在', async () => {
    const w = await mountWith(eps(12))

    expect(w.find('.pagination').exists()).toBe(false)
    expect(nums(w)).toEqual(Array.from({ length: 12 }, (_, i) => i + 1))
  })

  it('正好 100 集: 仍然没有分页控件(边界是"超过", 不是"达到")', async () => {
    const w = await mountWith(eps(100))

    expect(w.find('.pagination').exists()).toBe(false)
    expect(w.findAll('.ep-tile')).toHaveLength(100)
  })

  it('101 集: 出现分页控件, 而 DOM 里只有 50 个格子', async () => {
    const w = await mountWith(eps(101))

    expect(w.find('.pagination').exists()).toBe(true)
    expect(w.findAll('.ep-tile')).toHaveLength(50)
    expect(nums(w)[0]).toBe(1)
    expect(nums(w)[49]).toBe(50)
  })

  it('1005 集: 首屏第一张是第 1 集, 最后一张是第 50 集', async () => {
    const w = await mountWith(eps(1005))

    expect(nums(w)).toHaveLength(50)
    expect(nums(w)[0]).toBe(1)
    expect(nums(w)[49]).toBe(50)
  })

  it('翻到第 2 页: 第 51..100 集, 并回到剧集区顶部', async () => {
    const w = await mountWith(eps(1005))
    Element.prototype.scrollIntoView.mockClear()

    await clickPage(w, 2)

    expect(nums(w)[0]).toBe(51)
    expect(nums(w)[49]).toBe(100)
    // 不滚的话视口停在分页按钮那一行 —— 也就是新一页 50 张格子的**末尾**,
    // 看到的是中间而不是开头
    expect(Element.prototype.scrollIntoView)
      .toHaveBeenCalledWith({ block: 'start' })
  })

  it('再翻回第 1 页: 又是第 1..50 集', async () => {
    const w = await mountWith(eps(1005))

    await clickPage(w, 2)
    await clickPage(w, 1)

    expect(nums(w)[0]).toBe(1)
    expect(nums(w)[49]).toBe(50)
  })

  it('高亮与切片解耦: 第 60 集看过, 翻到第 2 页它仍然带 watched', async () => {
    const w = await mountWith(eps(1005), [60])

    // 第 1 页里没有第 60 集, 一个高亮也不该有
    expect(w.findAll('.ep-tile.watched')).toHaveLength(0)

    await clickPage(w, 2)

    const watched = w.findAll('.ep-tile.watched')
    expect(watched).toHaveLength(1)
    // 第 2 页是 51..100, 第 60 集落在第 10 个格子上(index 9)
    expect(w.findAll('.ep-tile')[9].classes()).toContain('watched')
    expect(nums(w)[9]).toBe(60)
  })

  it('「已看 N / M」的分母仍然是全量, 被切片改小就错了', async () => {
    const w = await mountWith(eps(1005), [1, 2, 3])

    // 3 集看过, 而这部番一共 1005 集 —— 不是 50
    expect(w.text()).toContain('已看 3 / 1005')
  })

  it('没有剧集时还是那句空态, 不出现分页控件', async () => {
    const w = await mountWith([])

    expect(w.find('.pagination').exists()).toBe(false)
    expect(w.findAll('.ep-tile')).toHaveLength(0)
    expect(w.text()).toContain('暂无剧集数据')
  })
})

/**
 * c98: 点格子(打卡)之后, 界面上的进度要跟服务端那一份对得上.
 *
 * 服务端在打勾成功时顺手把 progress 推到这一集, 本来没有追番记录还会建一条
 * (StatsService#syncProgressOnWatched). 前端不跟的话, 用户看到的数字与服务端
 * 存的是两回事, 而两边单独看都"成功". 这里钉的就是这个跟手的动作.
 *
 * 数字从 `.track-input-row` 那个框里读 —— 它是界面上 progress 唯一的显示处.
 * 追番栏本身是 `v-if="loggedIn && trackForm.id"`, 所以这些用例必须给一个
 * `tracked: true` 的追番状态, 否则框根本不渲染, 断言会退化成"找不到元素".
 */
describe('详情页剧集: 打勾联动进度', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    Element.prototype.scrollIntoView = vi.fn()
  })

  afterEach(() => {
    localStorage.clear()
    vi.restoreAllMocks()
  })

  /** 追番栏里那个进度数字框(第 0 个是进度, 第 1 个是评分) */
  const progressBox = w => w.findAll('.track-input-row input[type="number"]')[0]

  it('打上勾: 进度立刻推到这一集(不再需要手动改数字框)', async () => {
    const w = await mountWith(eps(12), [], { tracked: true, id: 55, status: 'watching', progress: 2, score: 0 })
    toggleEpisode.mockResolvedValue({ data: { code: 200, data: { watched: true, episodeNum: 7 } } })

    await w.findAll('.ep-tile')[6].trigger('click')
    await flushPromises()

    expect(progressBox(w).element.value).toBe('7')
    expect(w.findAll('.ep-tile')[6].classes()).toContain('watched')
  })

  it('往回的集号不动进度: 已经到 7 了, 补点第 3 集仍停在 7', async () => {
    // 与服务端同一个 max 口径 —— 直接赋值的话这里会变成 3
    const w = await mountWith(eps(12), [], { tracked: true, id: 55, status: 'watching', progress: 7, score: 0 })
    toggleEpisode.mockResolvedValue({ data: { code: 200, data: { watched: true, episodeNum: 3 } } })

    await w.findAll('.ep-tile')[2].trigger('click')
    await flushPromises()

    expect(progressBox(w).element.value).toBe('7')
    expect(w.findAll('.ep-tile')[2].classes()).toContain('watched')
  })

  it('取消打勾: 勾没了, 进度不许跟着退回去', async () => {
    const w = await mountWith(eps(12), [5], { tracked: true, id: 55, status: 'watching', progress: 5, score: 0 })
    toggleEpisode.mockResolvedValue({ data: { code: 200, data: { watched: false, episodeNum: 5 } } })

    expect(w.findAll('.ep-tile')[4].classes()).toContain('watched')
    await w.findAll('.ep-tile')[4].trigger('click')
    await flushPromises()

    expect(w.findAll('.ep-tile')[4].classes()).not.toContain('watched')
    expect(progressBox(w).element.value).toBe('5')
  })

  it('本来没追番: 打勾后重拉一次状态, 追番栏跟着出现并对齐到服务端那一份', async () => {
    // 这条是 c98 里最要紧的一格: 服务端刚替我们建了一条 watching 行, 而本地
    // trackForm 的 id 还是 null. 不重拉的话用户接着点「保存」, 那个陈旧的
    // status('want_to_watch')就把新记录覆盖掉, 那部番当场从首页「继续看」里消失.
    const w = await mountWith(eps(12), [], { tracked: false })
    expect(w.find('.d-track-bar').exists()).toBe(false)
    expect(getTrackingStatus).toHaveBeenCalledTimes(1)   // 挂载时那一次

    toggleEpisode.mockResolvedValue({ data: { code: 200, data: { watched: true, episodeNum: 3 } } })
    getTrackingStatus.mockResolvedValueOnce({
      data: { code: 200, data: { tracked: true, id: 66, status: 'watching', progress: 3, score: 0 } },
    })

    await w.findAll('.ep-tile')[2].trigger('click')
    await flushPromises()

    expect(getTrackingStatus).toHaveBeenCalledTimes(2)
    expect(w.find('.d-track-bar').exists()).toBe(true)
    expect(progressBox(w).element.value).toBe('3')
    // 状态也取自服务端, 不是本地那个初值 —— 保存时才不会把它改回「想看」
    expect(w.find('.track-status-btn.active').text()).toContain('在看')
  })

  it('已经追番的番不再多跑一次往返: 打勾只发 toggle 一个请求', async () => {
    const w = await mountWith(eps(12), [], { tracked: true, id: 55, status: 'watching', progress: 2, score: 0 })
    toggleEpisode.mockResolvedValue({ data: { code: 200, data: { watched: true, episodeNum: 4 } } })

    await w.findAll('.ep-tile')[3].trigger('click')
    await flushPromises()

    expect(getTrackingStatus).toHaveBeenCalledTimes(1)
    expect(getWatchedEpisodes).toHaveBeenCalledTimes(1)
  })

  it('打勾失败: 说出来, 而不是静默吞掉(改前是空 catch)', async () => {
    const w = await mountWith(eps(12), [], { tracked: true, id: 55, status: 'watching', progress: 2, score: 0 })
    const err = new Error('boom')
    err.response = { status: 500 }
    toggleEpisode.mockRejectedValue(err)

    await w.findAll('.ep-tile')[5].trigger('click')
    await flushPromises()

    expect(toastSpy).toHaveBeenCalledWith('标记失败：服务端返回 500')
    // 勾也不该留在界面上 —— 服务端没接下来
    expect(w.findAll('.ep-tile')[5].classes()).not.toContain('watched')
    expect(progressBox(w).element.value).toBe('2')
  })
})
