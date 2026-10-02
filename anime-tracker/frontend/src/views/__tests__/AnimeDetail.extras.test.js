import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createMemoryHistory } from 'vue-router'

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
  // 这个文件量的是它们三个, 但整份清单一个都不能少 —— vi.mock 是**整体替换**模块
  getSubjectCharacters: vi.fn(),
  getSubjectStaff: vi.fn(),
  getSubjectRelations: vi.fn(),
  likeReview: vi.fn(),
  unlikeReview: vi.fn(),
  getReviewLikers: vi.fn(),
  getReplies: vi.fn(),
  addReply: vi.fn(),
  editReply: vi.fn(),
  deleteReply: vi.fn(),
  likeReply: vi.fn(),
  unlikeReply: vi.fn(),
  getReplyLikers: vi.fn(),
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
  getWatchedEpisodes, getAnimeHeat, getTrackingStatus, getMyReview,
  getSubjectCharacters, getSubjectStaff, getSubjectRelations,
} from '../../api'

/**
 * 详情页底部那两块附属数据: 「角色与制作人员」与「关联作品」.
 *
 * 单独一个文件, 理由与 AnimeDetail.scores.test.js 同一个 —— 这里造的三种形状
 * (成功 / 完整取回但为空 / 取不到) 不该影响别的用例.
 *
 * <b>这个文件的头一条用例是"哨兵", 别删。</b> 上面那个工厂是**整体替换**整个 api 模块:
 * 少写一个导出, 组件里拿到的就是 undefined, 而那次调用落在 Promise.allSettled 的
 * 回调里 —— 表现是那一段安静地什么都不渲染, 页面其余部分完好, **别的测试文件一条都
 * 不会红**. 所以这里必须有一条断言"这三个接口真的被调了", 它就是"工厂补全了"的哨兵.
 *
 * 另一半是这三块的**渲染规则**, 分成三种情况, 三种都必须钉住:
 *   - 有数据            → 渲染;
 *   - 完整取回但为空    → 整段不渲染(一部确实没有角色数据的番, 底下挂一行"暂无"只是
 *                        在提示用户这里本该有东西);
 *   - 取不到且没有旧数据 → 那一行给「加载失败 · 重试」.
 * 后端在这三种情况下分别回 200+数据 / 200+[] / 502, 所以前两种在协议层就已经分开了;
 * 这一层要证的是前端没有把它们又合回去.
 */

const router = createRouter({
  history: createMemoryHistory(),
  routes: [{ path: '/anime/:id', component: { template: '<div />' } }],
})

const SUBJECT = { id: 7, nameCn: '某番', totalEpisodes: 12, images: { large: 'x.jpg' } }

/** 三个造数据的: 形状抄 SubjectExtrasDTO(角色带 actors, 声优可以一个都没有) */
const charItem = (id, name, actors = []) =>
  ({ id, name, relation: '主角', image: '/api/img?url=x', actors })
const staffItem = (id, name, relation) => ({ id, name, relation, image: '/api/img?url=y' })
const relItem = (id, name, nameCn, relation) => ({ id, name, nameCn, relation, image: '/api/img?url=z' })

async function mountDetail() {
  localStorage.setItem('anime_user', JSON.stringify({ id: 1, username: 'alice', token: 'jwt', role: 'USER' }))
  setActivePinia(createPinia())

  await router.push('/anime/7')
  await router.isReady()

  const wrapper = mount(AnimeDetail, { global: { plugins: [router] } })
  await flushPromises()
  return wrapper
}

/**
 * 这一页会调的接口给一套默认值.
 *
 * 附属数据那三个默认给**空数组**(= 完整取回但为空): 于是默认状态下页面一个字都不多
 * 渲染, 每条用例只需要描述它自己关心的那一块.
 */
function stubApis() {
  getAnimeDetail.mockResolvedValue({ data: { code: 200, data: SUBJECT } })
  getEpisodes.mockResolvedValue({ data: { code: 200, data: [] } })
  getRatingStats.mockResolvedValue({
    data: { code: 200, data: { average: 0, count: 0, distribution: Array(10).fill(0) } },
  })
  getSubjectReviews.mockResolvedValue({ data: { code: 200, data: [] } })
  getMyReview.mockResolvedValue({ data: { code: 200, data: { exists: false } } })
  getTrackingStatus.mockResolvedValue({ data: { code: 200, data: { tracked: false } } })
  getWatchedEpisodes.mockResolvedValue({ data: { code: 200, data: [] } })
  getAnimeHeat.mockResolvedValue({ data: { code: 200, data: null } })
  // 「你可能也喜欢」那一块: 让它空着, 于是页面上的 .related-card 只可能来自
  // 「关联作品」—— 两个区块用的是同一套类名(它们本来就是同一种卡片)
  getFiltered.mockResolvedValue({ data: { code: 200, data: { list: [], total: 0 } } })

  stubExtras()
}

function stubExtras({ characters = [], staff = [], relations = [] } = {}) {
  getSubjectCharacters.mockResolvedValue({ data: { code: 200, data: characters } })
  getSubjectStaff.mockResolvedValue({ data: { code: 200, data: staff } })
  getSubjectRelations.mockResolvedValue({ data: { code: 200, data: relations } })
}

/** 页面上的某一段(按标题找 —— 详情页的 .d-section 有好几段) */
const section = (w, title) =>
  w.findAll('.d-section').find(s => s.find('.sec-title').exists() && s.find('.sec-title').text() === title)

describe('详情页的附属数据', () => {
  beforeEach(() => {
    localStorage.clear()
    vi.clearAllMocks()
    stubApis()
  })

  /**
   * 哨兵: 这个文件里别的用例都不足以为"工厂漏了导出"报警 ——
   * 漏了之后那一次调用是对 undefined 的调用, 三块一起安静地不渲染,
   * 而"不渲染"正是另外几条用例里的合法期望.
   */
  it('三个接口真的各被调了一次(工厂漏一个导出就会在这里红)', async () => {
    await mountDetail()

    expect(getSubjectCharacters).toHaveBeenCalledWith(7)
    expect(getSubjectStaff).toHaveBeenCalledWith(7)
    expect(getSubjectRelations).toHaveBeenCalledWith(7)
  })

  it('三块都取回来时各自渲染: 角色带 CV, 制作人员带职务, 关联作品带关系角标', async () => {
    stubExtras({
      characters: [charItem(1, '角色甲', [{ id: 100, name: '声优甲' }]), charItem(2, '角色乙')],
      staff: [staffItem(3, '监督甲', '监督')],
      relations: [relItem(4, '前作', '前作中文名', '前传')],
    })
    const w = await mountDetail()

    // 角色与制作人员在**同一段**里(两排横向滚动), 所以数卡片的总数
    expect(w.text()).toContain('角色与制作人员')
    expect(w.findAll('.ex-card').length).toBe(3)
    expect(w.text()).toContain('CV 声优甲')

    const rel = section(w, '关联作品')
    expect(rel).toBeTruthy()
    // nameCn 优先(与全站同一套约定), 角标上的关系是这一块**唯一**多说出来的信息 ——
    // 掉了它就只是一排普通封面, 与上面「你可能也喜欢」长得一模一样
    expect(rel.find('.rc-title').text()).toBe('前作中文名')
    expect(rel.find('.rc-badge').text()).toBe('前传')
  })

  /**
   * 「完整取回但为空」→ 整段不渲染.
   *
   * ⚠️ 这条必须与下面那条**配对**看: 单有这一条的话, 一个"永远不渲染"的实现
   *    (比如取数那段整个没接线)也会绿.
   */
  it('完整取回但为空: 这两段整段不渲染, 也不留一行"暂无"', async () => {
    const w = await mountDetail()

    expect(w.text()).not.toContain('角色与制作人员')
    expect(w.text()).not.toContain('关联作品')
    expect(w.find('.ex-error').exists()).toBe(false)
  })

  it('对照: 有数据时这两段确实是渲染出来的(上一条不是因为整页没渲染)', async () => {
    stubExtras({ characters: [charItem(1, '角色甲')], relations: [relItem(4, '前作', '前作', '前传')] })
    const w = await mountDetail()

    expect(w.text()).toContain('角色与制作人员')
    expect(section(w, '关联作品')).toBeTruthy()
  })

  /**
   * <b>一块失败不连坐</b> —— 这一条钉的正是 allSettled 与 all 的差别.
   *
   * 换成 Promise.all: persons 那一行 reject 会让 .forEach 整个跳过去, 于是角色与关联
   * 作品即使已经把数据拿回来了也不会写进状态(它们的 status 还停在 loading), 三块一起
   * 空掉 —— 而那时"取不到的那一块显示了加载失败"这条期望**依然是绿的**.
   */
  it('只有一块取不到时: 那一行给「加载失败 · 重试」, 另外两块照常渲染', async () => {
    stubExtras({
      characters: [charItem(1, '角色甲')],
      relations: [relItem(4, '前作', '前作', '前传')],
    })
    getSubjectStaff.mockRejectedValue(new Error('502'))
    const w = await mountDetail()

    expect(w.findAll('.ex-card').length).toBe(1)
    expect(section(w, '关联作品')).toBeTruthy()

    expect(w.findAll('.ex-error').length).toBe(1)
    expect(w.find('.ex-error').text()).toContain('加载失败')
    expect(w.find('.ex-error').text()).toContain('重试')
  })

  it('点重试只重取失败的那一块, 另外两块一次都不再问', async () => {
    stubExtras({ characters: [charItem(1, '角色甲')] })
    getSubjectStaff.mockRejectedValueOnce(new Error('502'))
    const w = await mountDetail()

    getSubjectStaff.mockResolvedValue({
      data: { code: 200, data: [staffItem(3, '监督甲', '监督')] },
    })
    await w.find('.ex-retry').trigger('click')
    await flushPromises()

    expect(w.text()).toContain('监督甲')
    expect(w.find('.ex-error').exists()).toBe(false)
    expect(getSubjectStaff).toHaveBeenCalledTimes(2)
    expect(getSubjectCharacters).toHaveBeenCalledTimes(1)
    expect(getSubjectRelations).toHaveBeenCalledTimes(1)
  })

  it('没有声优的角色卡不渲染 CV 那一行(实测 128 条角色里 63 条就没有声优)', async () => {
    stubExtras({
      characters: [charItem(1, '有 CV 的', [{ id: 100, name: '声优甲' }]), charItem(2, '没有的')],
    })
    const w = await mountDetail()

    const cards = w.findAll('.ex-card')
    expect(cards.length).toBe(2)
    expect(cards[0].find('.ex-sub').text()).toBe('CV 声优甲')
    expect(cards[1].find('.ex-sub').exists()).toBe(false)
  })

  it('关联作品的卡片点得动, 回车与空格也都收', async () => {
    stubExtras({ relations: [relItem(33, '前作', '前作中文名', '前传')] })
    const w = await mountDetail()

    const card = w.find('.related-card')
    expect(card.attributes('role')).toBe('button')
    expect(card.attributes('tabindex')).toBe('0')

    await card.trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/anime/33')

    // role=button 的约定是回车和空格都触发. 只测回车的话, "按空格没反应"
    // 会成为一个只有键盘用户才会撞上的 bug
    await router.push('/anime/7')
    await w.find('.related-card').trigger('keydown.enter')
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/anime/33')

    await router.push('/anime/7')
    await w.find('.related-card').trigger('keydown.space')
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/anime/33')
  })
})
