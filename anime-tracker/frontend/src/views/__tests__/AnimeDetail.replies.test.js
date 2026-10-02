import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
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
  // 附属数据那三个. 一个都不能漏 —— 理由见 AnimeDetail.test.js 里同一处
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
  getAnimeDetail, getEpisodes, getRatingStats, getSubjectReviews,
  getMyReview, getTrackingStatus, getWatchedEpisodes, getAnimeHeat,
  getReplies, addReply, editReply, deleteReply,
  likeReply, unlikeReply, getReplyLikers,
} from '../../api'

/**
 * 详情页的回复区.
 *
 * 盯的是这几件从界面上看不出来的事:
 *
 *   · 「已编辑」标记会不会出现 —— 它由两个时间戳比出来(服务端不给布尔), 而编辑后
 *     如果只改本地的 content、不拿服务端回的那整条替换, 改完就是"内容变了、标记没出现";
 *   · 计数是本地加减还是服务端给的 —— 这里的取舍与点赞**相反**(发/删回复不幂等,
 *     所以本地 ±1 是这次写入的结果, 不是猜的), 要用例把这条差别钉住;
 *   · 删除按钮的可见性有没有与后端那三支权限对齐 —— 少了"评论作者"那一支, 楼主的
 *     删除按钮就消失了, 而服务端其实是允许的;
 *   · 懒加载有没有真的懒 —— 每条评论都在挂载时拉一遍回复, 一页 20 条就是 20 个请求;
 *   · 访客点回复按钮是"展开看"还是"被弹去登录" —— 回复列表是公开的, 拦在阅读上
 *     就与评论列表本身公开这件事自相矛盾.
 *
 * ⚠️ api mock 是**整体替换**模块, 组件用到的每个导出都要列上, 漏一个就在解构 .data
 * 时抛, 整页落到错误态, 后面所有断言都会变成"找不到元素"(看起来像功能没做)。
 * 详情页另外三个测试文件里也各有一份同样的 mock, 加接口时四处都要补。
 */

const router = createRouter({
  history: createMemoryHistory(),
  routes: [
    { path: '/anime/:id', component: { template: '<div />' } },
    { path: '/login', component: { template: '<div />' } },
  ],
})

const SUBJECT = { id: 7, nameCn: '某番', totalEpisodes: 12, images: { large: 'x.jpg' } }

/** 一条评论. replyCount 是 V8 加在 review 表上的那列, 列表里直接读它 */
function review(overrides = {}) {
  return {
    id: 1, userId: 9, username: 'bob', rating: 8, content: '好看',
    createdAt: '2026-01-01T00:00:00', isOwner: false,
    likeCount: 0, likedByMe: false, replyCount: 0,
    ...overrides,
  }
}

/** 一条回复. updatedAt 与 createdAt 默认相同 = 没改过 */
function reply(overrides = {}) {
  return {
    id: 11, userId: 20, username: 'carol', content: '同感',
    createdAt: '2026-01-02T00:00:00', updatedAt: '2026-01-02T00:00:00',
    isOwner: false, likeCount: 0, likedByMe: false,
    ...overrides,
  }
}

const ok = data => ({ data: { code: 200, data } })

async function mountDetail({ loggedIn = true, reviews = [review()], replies = [] } = {}) {
  if (loggedIn) {
    localStorage.setItem('anime_user', JSON.stringify({ id: 1, username: 'alice', token: 'jwt', role: 'USER' }))
  }
  setActivePinia(createPinia())

  getAnimeDetail.mockResolvedValue(ok(SUBJECT))
  getEpisodes.mockResolvedValue(ok([]))
  getRatingStats.mockResolvedValue(ok({ average: 8, count: 1, distribution: Array(10).fill(0) }))
  getSubjectReviews.mockResolvedValue(ok(reviews))
  getMyReview.mockResolvedValue(ok({ exists: false }))
  getTrackingStatus.mockResolvedValue(ok({ tracked: false }))
  getWatchedEpisodes.mockResolvedValue(ok([]))
  getAnimeHeat.mockResolvedValue(ok(null))
  getReplies.mockResolvedValue(ok(replies))

  await router.push('/anime/7')
  await router.isReady()

  const wrapper = mount(AnimeDetail, { global: { plugins: [router] } })
  await flushPromises()
  return wrapper
}

/** 第 i 条评论那一块 */
const itemAt = (wrapper, i) => wrapper.findAll('.rv-item')[i]
/** 那条评论的「回复」按钮 */
const replyBtnAt = (wrapper, i) => itemAt(wrapper, i).find('.rv-act-reply')
/** 点开回复区 */
async function openReplies(wrapper, i = 0) {
  await replyBtnAt(wrapper, i).trigger('click')
  await flushPromises()
}
/** 按正文找某个回复块里的一个按钮(编辑/删除/谁赞了都是同一类) */
const actByText = (root, text) => root.findAll('.rp-act').find(b => b.text() === text)
const replyItemAt = (wrapper, i) => wrapper.findAll('.rp-item')[i]

describe('回复区的展开', () => {
  beforeEach(() => {
    localStorage.clear()
    vi.clearAllMocks()
    vi.stubGlobal('confirm', () => true)
  })
  afterEach(() => vi.unstubAllGlobals())

  it('展开才拉回复(懒加载), 正文与用户名真的渲染出来', async () => {
    const wrapper = await mountDetail({
      reviews: [review({ replyCount: 2 })],
      replies: [reply(), reply({ id: 12, username: 'dave', content: '我也觉得' })],
    })

    // 挂载时一个回复请求都不该发: 一页 20 条评论就是 20 个请求, 而多数没人展开
    expect(getReplies).not.toHaveBeenCalled()
    expect(wrapper.find('.rp-area').exists()).toBe(false)

    await openReplies(wrapper)

    expect(getReplies).toHaveBeenCalledWith(1)
    expect(wrapper.findAll('.rp-item')).toHaveLength(2)
    expect(wrapper.find('.rp-list').text()).toContain('我也觉得')
  })

  it('收起再展开不重拉(拉过一次就留着)', async () => {
    const wrapper = await mountDetail({ reviews: [review({ replyCount: 1 })], replies: [reply()] })

    await openReplies(wrapper)
    await openReplies(wrapper)          // 收起
    expect(wrapper.find('.rp-area').exists()).toBe(false)
    await openReplies(wrapper)          // 再展开

    expect(wrapper.findAll('.rp-item')).toHaveLength(1)
    expect(getReplies).toHaveBeenCalledTimes(1)
  })

  it('回复数为 0 时按钮上不显示 "0"(满屏的 0 是噪音)', async () => {
    const wrapper = await mountDetail({ reviews: [review({ replyCount: 0 })] })

    expect(replyBtnAt(wrapper, 0).text()).toBe('')
  })

  it('访客也能展开看回复 —— 回复列表是公开的, 拦在阅读上与评论公开自相矛盾', async () => {
    const wrapper = await mountDetail({
      loggedIn: false, reviews: [review({ replyCount: 1 })], replies: [reply()],
    })

    await openReplies(wrapper)

    expect(getReplies).toHaveBeenCalledWith(1)
    expect(wrapper.findAll('.rp-item')).toHaveLength(1)
    // 但输入框换成一句登录提示 —— 访客没有 composer
    expect(wrapper.find('.rp-composer').exists()).toBe(false)
    expect(wrapper.find('.rp-area .rp-hint a').text()).toContain('登录')
  })

  it('一条回复都没有时给空态, 不留一个空白框', async () => {
    const wrapper = await mountDetail()
    await openReplies(wrapper)

    expect(wrapper.find('.rp-hint').text()).toContain('还没有回复')
  })

  it('回复拉不到时把这块收起来, 不留一个永远转圈的「加载中…」', async () => {
    const wrapper = await mountDetail({ reviews: [review({ replyCount: 1 })] })
    // 必须在 mountDetail **之后**才改成拒绝: 它自己会把 getReplies 设成成功
    getReplies.mockRejectedValue(new Error('boom'))

    await openReplies(wrapper)

    expect(wrapper.find('.rp-area').exists()).toBe(false)
    // 计数还在按钮上, 再点一次就是重试
    expect(replyBtnAt(wrapper, 0).text()).toContain('1')
  })
})

describe('发回复', () => {
  beforeEach(() => {
    localStorage.clear()
    vi.clearAllMocks()
    vi.stubGlobal('confirm', () => true)
  })
  afterEach(() => vi.unstubAllGlobals())

  it('发一条: 调 addReply(评论id, 正文), 列表里多一条, 计数 +1, 草稿清空', async () => {
    addReply.mockResolvedValue(ok(reply({ id: 99, isOwner: true, content: '我来说两句' })))
    const wrapper = await mountDetail({ reviews: [review({ replyCount: 0 })] })
    await openReplies(wrapper)

    await wrapper.find('.rp-composer .rp-input').setValue('我来说两句')
    await wrapper.find('.rp-composer .rp-send').trigger('click')
    await flushPromises()

    expect(addReply).toHaveBeenCalledWith(1, '我来说两句')
    expect(wrapper.findAll('.rp-item')).toHaveLength(1)
    expect(wrapper.find('.rp-list').text()).toContain('我来说两句')
    // 计数本地 +1 —— 与点赞相反, 这里可以加: 发回复不幂等, 服务端确实新建了一行
    expect(replyBtnAt(wrapper, 0).text()).toContain('1')
    // 草稿清掉, 否则同一句话会被再发一次
    expect(wrapper.find('.rp-composer .rp-input').element.value).toBe('')
  })

  it('正文首尾的空白先裁掉再发, 内容为空时一个请求都不发', async () => {
    const wrapper = await mountDetail()
    await openReplies(wrapper)

    await wrapper.find('.rp-composer .rp-input').setValue('   ')
    await wrapper.find('.rp-composer .rp-send').trigger('click')
    await flushPromises()

    expect(addReply).not.toHaveBeenCalled()
  })

  it('连点两下只发一个请求(发回复不幂等, 少这道闸会真的多出几条)', async () => {
    let release
    addReply.mockReturnValue(new Promise(r => { release = () => r(ok(reply({ id: 99 }))) }))
    const wrapper = await mountDetail()
    await openReplies(wrapper)

    await wrapper.find('.rp-composer .rp-input').setValue('只此一条')
    const btn = wrapper.find('.rp-composer .rp-send')
    await btn.trigger('click')
    await btn.trigger('click')          // 第一个请求还没回来
    release()
    await flushPromises()

    expect(addReply).toHaveBeenCalledTimes(1)
  })

  it('发送失败: 计数不涨, 列表也不多一条(界面不能与库各说各话)', async () => {
    addReply.mockRejectedValue(new Error('boom'))
    const wrapper = await mountDetail({ reviews: [review({ replyCount: 0 })] })
    await openReplies(wrapper)

    await wrapper.find('.rp-composer .rp-input').setValue('发不出去')
    await wrapper.find('.rp-composer .rp-send').trigger('click')
    await flushPromises()

    expect(wrapper.findAll('.rp-item')).toHaveLength(0)
    expect(replyBtnAt(wrapper, 0).text()).toBe('')
  })
})

describe('改回复', () => {
  beforeEach(() => {
    localStorage.clear()
    vi.clearAllMocks()
    vi.stubGlobal('confirm', () => true)
  })
  afterEach(() => vi.unstubAllGlobals())

  it('自己的回复才有「编辑」「删除」, 别人的一条都没有', async () => {
    const wrapper = await mountDetail({
      reviews: [review({ replyCount: 2 })],
      replies: [reply({ id: 11, isOwner: true }), reply({ id: 12, isOwner: false })],
    })
    await openReplies(wrapper)

    expect(actByText(replyItemAt(wrapper, 0), '编辑')).toBeTruthy()
    expect(actByText(replyItemAt(wrapper, 0), '删除')).toBeTruthy()
    expect(actByText(replyItemAt(wrapper, 1), '编辑')).toBeUndefined()
    expect(actByText(replyItemAt(wrapper, 1), '删除')).toBeUndefined()
  })

  it('评论作者能删自己楼里的回复(后端那三支权限里的第二支)', async () => {
    const wrapper = await mountDetail({
      reviews: [review({ isOwner: true, replyCount: 1 })],
      replies: [reply({ isOwner: false })],   // 别人发的
    })
    await openReplies(wrapper)

    const item = replyItemAt(wrapper, 0)
    expect(actByText(item, '编辑')).toBeUndefined()   // 改不行: 改是替别人说话
    expect(actByText(item, '删除')).toBeTruthy()      // 删可以: 我的地盘
  })

  it('编辑框里是这条回复**原来的**正文, 不是空框', async () => {
    const wrapper = await mountDetail({
      reviews: [review({ replyCount: 1 })],
      replies: [reply({ id: 11, isOwner: true, content: '原来的话' })],
    })
    await openReplies(wrapper)

    await actByText(replyItemAt(wrapper, 0), '编辑').trigger('click')
    await flushPromises()

    expect(wrapper.find('.rp-item .rp-input').element.value).toBe('原来的话')
  })

  it('保存后拿服务端回的那整条替换 —— 「已编辑」标记才会出现', async () => {
    editReply.mockResolvedValue(ok(reply({
      id: 11, isOwner: true, content: '改过的话',
      createdAt: '2026-01-02T00:00:00', updatedAt: '2026-01-03T00:00:00',
    })))
    const wrapper = await mountDetail({
      reviews: [review({ replyCount: 1 })],
      replies: [reply({ id: 11, isOwner: true, content: '原来的话' })],
    })
    await openReplies(wrapper)

    // 改之前没有这个标记
    expect(wrapper.find('.rp-edited').exists()).toBe(false)

    await actByText(replyItemAt(wrapper, 0), '编辑').trigger('click')
    await wrapper.find('.rp-item .rp-input').setValue('改过的话')
    await replyItemAt(wrapper, 0).find('.rp-send').trigger('click')
    await flushPromises()

    expect(editReply).toHaveBeenCalledWith(11, '改过的话')
    expect(replyItemAt(wrapper, 0).text()).toContain('改过的话')
    expect(replyItemAt(wrapper, 0).find('.rp-edited').text()).toBe('已编辑')
  })

  it('取消编辑不发请求, 也退出编辑态', async () => {
    const wrapper = await mountDetail({
      reviews: [review({ replyCount: 1 })],
      replies: [reply({ id: 11, isOwner: true, content: '原来的话' })],
    })
    await openReplies(wrapper)

    await actByText(replyItemAt(wrapper, 0), '编辑').trigger('click')
    await replyItemAt(wrapper, 0).find('.rp-cancel').trigger('click')
    await flushPromises()

    expect(editReply).not.toHaveBeenCalled()
    expect(actByText(replyItemAt(wrapper, 0), '编辑')).toBeTruthy()
  })

  it('改完正文为空不发请求', async () => {
    const wrapper = await mountDetail({
      reviews: [review({ replyCount: 1 })],
      replies: [reply({ id: 11, isOwner: true })],
    })
    await openReplies(wrapper)

    await actByText(replyItemAt(wrapper, 0), '编辑').trigger('click')
    await wrapper.find('.rp-item .rp-input').setValue('   ')
    await replyItemAt(wrapper, 0).find('.rp-send').trigger('click')
    await flushPromises()

    expect(editReply).not.toHaveBeenCalled()
  })
})

describe('删回复', () => {
  beforeEach(() => {
    localStorage.clear()
    vi.clearAllMocks()
    vi.stubGlobal('confirm', () => true)
  })
  afterEach(() => vi.unstubAllGlobals())

  it('确认后调 deleteReply, 列表少一条, 计数减一', async () => {
    deleteReply.mockResolvedValue(ok(null))
    const wrapper = await mountDetail({
      reviews: [review({ replyCount: 2 })],
      replies: [reply({ id: 11, isOwner: true }), reply({ id: 12 })],
    })
    await openReplies(wrapper)

    await actByText(replyItemAt(wrapper, 0), '删除').trigger('click')
    await flushPromises()

    expect(deleteReply).toHaveBeenCalledWith(11)
    expect(wrapper.findAll('.rp-item')).toHaveLength(1)
    expect(replyBtnAt(wrapper, 0).text()).toContain('1')
  })

  it('点「取消」什么都不做', async () => {
    vi.stubGlobal('confirm', () => false)
    const wrapper = await mountDetail({
      reviews: [review({ replyCount: 1 })],
      replies: [reply({ id: 11, isOwner: true })],
    })
    await openReplies(wrapper)

    await actByText(replyItemAt(wrapper, 0), '删除').trigger('click')
    await flushPromises()

    expect(deleteReply).not.toHaveBeenCalled()
    expect(wrapper.findAll('.rp-item')).toHaveLength(1)
  })

  it('删除失败: 列表与计数都不动', async () => {
    deleteReply.mockRejectedValue(new Error('boom'))
    const wrapper = await mountDetail({
      reviews: [review({ replyCount: 1 })],
      replies: [reply({ id: 11, isOwner: true })],
    })
    await openReplies(wrapper)

    await actByText(replyItemAt(wrapper, 0), '删除').trigger('click')
    await flushPromises()

    expect(wrapper.findAll('.rp-item')).toHaveLength(1)
    expect(replyBtnAt(wrapper, 0).text()).toContain('1')
  })
})

describe('回复的点赞', () => {
  beforeEach(() => {
    localStorage.clear()
    vi.clearAllMocks()
    vi.stubGlobal('confirm', () => true)
  })
  afterEach(() => vi.unstubAllGlobals())

  const likeBtn = wrapper => replyItemAt(wrapper, 0).find('.rp-act')

  it('计数取服务端回的那一份, 不是本地 +1', async () => {
    // 服务端说 5 —— 而不是"本来 2, 本地 +1"那个同样是 3 的巧合值
    likeReply.mockResolvedValue(ok({ liked: true, likeCount: 5 }))
    const wrapper = await mountDetail({
      reviews: [review({ replyCount: 1 })],
      replies: [reply({ id: 11, likeCount: 2, likedByMe: false })],
    })
    await openReplies(wrapper)

    expect(likeBtn(wrapper).classes()).not.toContain('on')
    await likeBtn(wrapper).trigger('click')
    await flushPromises()

    expect(likeReply).toHaveBeenCalledWith(11)
    expect(unlikeReply).not.toHaveBeenCalled()
    expect(likeBtn(wrapper).classes()).toContain('on')
    expect(likeBtn(wrapper).text()).toContain('5')
  })

  it('已赞时点一下走取消', async () => {
    unlikeReply.mockResolvedValue(ok({ liked: false, likeCount: 1 }))
    const wrapper = await mountDetail({
      reviews: [review({ replyCount: 1 })],
      replies: [reply({ id: 11, likeCount: 2, likedByMe: true })],
    })
    await openReplies(wrapper)

    await likeBtn(wrapper).trigger('click')
    await flushPromises()

    expect(unlikeReply).toHaveBeenCalledWith(11)
    expect(likeReply).not.toHaveBeenCalled()
    expect(likeBtn(wrapper).classes()).not.toContain('on')
    expect(likeBtn(wrapper).text()).toContain('1')
  })

  it('访客点回复的赞: 送去登录页, 一个请求都不发', async () => {
    const wrapper = await mountDetail({
      loggedIn: false, reviews: [review({ replyCount: 1 })], replies: [reply({ id: 11 })],
    })
    await openReplies(wrapper)

    await likeBtn(wrapper).trigger('click')
    await flushPromises()

    expect(router.currentRoute.value.path).toBe('/login')
    expect(likeReply).not.toHaveBeenCalled()
  })

  it('一个赞都没有时不摆「谁赞了」, 有时才拉名单', async () => {
    getReplyLikers.mockResolvedValue(ok({ total: 1, list: [{ userId: 3, username: 'bob' }] }))
    const wrapper = await mountDetail({
      reviews: [review({ replyCount: 2 })],
      replies: [reply({ id: 11, likeCount: 0 }), reply({ id: 12, likeCount: 1 })],
    })
    await openReplies(wrapper)

    expect(actByText(replyItemAt(wrapper, 0), '谁赞了')).toBeUndefined()

    await actByText(replyItemAt(wrapper, 1), '谁赞了').trigger('click')
    await flushPromises()

    expect(getReplyLikers).toHaveBeenCalledWith(12)
    expect(replyItemAt(wrapper, 1).find('.rp-liker').text()).toBe('bob')
  })
})
