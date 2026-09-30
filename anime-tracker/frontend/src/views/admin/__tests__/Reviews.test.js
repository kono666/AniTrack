import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'

vi.mock('../../../api', () => ({
  getAdminReviews: vi.fn(),
  adminDeleteReview: vi.fn(),
}))

// 提示是模块作用域的一份全局状态(见 useToast 的说明), 断言渲染出来的 DOM
// 要先把 Toast.vue 挂上; 换成 spy 就只问"有没有提示、说的是什么"
const { toastSpy } = vi.hoisted(() => ({ toastSpy: vi.fn() }))
vi.mock('../../../composables/useToast', () => ({
  useToast: () => ({ show: toastSpy, remove: vi.fn(), items: { value: [] } }),
  showToast: toastSpy,
}))

import Reviews from '../Reviews.vue'
import { getAdminReviews, adminDeleteReview } from '../../../api'

/**
 * 后台评论页的删除与那两个计数.
 *
 * 这个文件的删除路径**在此之前完全没有测试**(adminLoadError.test.js 只覆盖了
 * 加载失败那一支). 没有测试的写路径最容易悄悄坏掉, 而它坏掉的样子是:
 * 弹窗点了确定、接口也调了, 列表却还留着那一行 —— 管理员会再点一次.
 *
 * 还有一件事: 赞数/回复数是给"这条该不该处理"当参考的. 老后端不返回这两个字段时
 * 必须**整项不渲染**, 而不是摆一个「0」—— 那是在替后端编数字, 而且会让人以为
 * 这条评论下面真的什么都没有.
 */

const router = createRouter({
  history: createMemoryHistory(),
  routes: [{ path: '/admin/reviews', component: { template: '<div />' } }],
})

const ok = data => ({ data: { code: 200, data } })

function review(overrides = {}) {
  return {
    id: 1, userId: 9, username: 'bob', rating: 8, content: '好看',
    subjectId: 101, createdAt: '2026-01-01T00:00:00',
    likeCount: 2, replyCount: 3,
    ...overrides,
  }
}

async function mountReviews(reviews = [review()]) {
  getAdminReviews.mockResolvedValue(ok(reviews))

  await router.push('/admin/reviews')
  await router.isReady()

  const wrapper = mount(Reviews, { global: { plugins: [router] } })
  await flushPromises()
  return wrapper
}

describe('后台评论页', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.stubGlobal('confirm', () => true)
  })
  afterEach(() => vi.unstubAllGlobals())

  it('赞数与回复数都摆出来', async () => {
    const wrapper = await mountReviews([review({ likeCount: 2, replyCount: 3 })])

    const sub = wrapper.find('.review-subject').text()
    expect(sub).toContain('2')
    expect(sub).toContain('3')
    expect(wrapper.findAll('.review-likes')).toHaveLength(2)
  })

  it('后端没给这两个字段时整项不渲染, 不摆一个「0」', async () => {
    const wrapper = await mountReviews([review({ likeCount: undefined, replyCount: undefined })])

    expect(wrapper.findAll('.review-likes')).toHaveLength(0)
  })

  it('确认后调接口, 并把那一行从本地列表里去掉', async () => {
    adminDeleteReview.mockResolvedValue(ok(null))
    const wrapper = await mountReviews([review({ id: 1 }), review({ id: 2, username: 'carol' })])

    await wrapper.findAll('.delete-btn')[0].trigger('click')
    await flushPromises()

    expect(adminDeleteReview).toHaveBeenCalledWith(1)
    expect(wrapper.findAll('.review-row')).toHaveLength(1)
    expect(wrapper.text()).not.toContain('bob')
  })

  it('弹窗里点名是谁的评论(删错人的代价是别人的东西没了)', async () => {
    adminDeleteReview.mockResolvedValue(ok(null))
    const confirmSpy = vi.fn(() => true)
    vi.stubGlobal('confirm', confirmSpy)
    const wrapper = await mountReviews()

    await wrapper.find('.delete-btn').trigger('click')
    await flushPromises()

    expect(confirmSpy.mock.calls[0][0]).toContain('bob')
  })

  it('点「取消」一个请求都不发, 那一行还在', async () => {
    vi.stubGlobal('confirm', () => false)
    const wrapper = await mountReviews()

    await wrapper.find('.delete-btn').trigger('click')
    await flushPromises()

    expect(adminDeleteReview).not.toHaveBeenCalled()
    expect(wrapper.findAll('.review-row')).toHaveLength(1)
  })

  it('删除失败: 那一行留着, 而且给出服务端说的原因', async () => {
    adminDeleteReview.mockRejectedValue({ response: { data: { message: '评论不存在' } } })
    const wrapper = await mountReviews()

    await wrapper.find('.delete-btn').trigger('click')
    await flushPromises()

    // 行还在 —— 否则管理员会以为删成功了, 刷新一下它又回来
    expect(wrapper.findAll('.review-row')).toHaveLength(1)
    // 说的服务端给的原因, 不是一句笼统的「删除失败」
    expect(toastSpy).toHaveBeenCalledWith('评论不存在', 'error')
  })
})
