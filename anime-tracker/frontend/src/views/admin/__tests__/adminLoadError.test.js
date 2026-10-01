import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'
import { createPinia, setActivePinia } from 'pinia'

vi.mock('../../../api', () => ({
  getDashboard: vi.fn(),
  getAdminUsers: vi.fn(),
  getAdminReviews: vi.fn(),
  toggleUserStatus: vi.fn(),
  setUserRole: vi.fn(),
  unlockUser: vi.fn(),
  adminDeleteReview: vi.fn(),
  // 常量也要给: 这是**整体替换**而不是部分替换, 漏掉的导出在导入侧是 undefined,
  // 而 `ref(undefined)` 不会报错 —— 它会安静地把 limit 变成"没有每页条数",
  // 直到某个断言因为别的原因为红才被发现. (AdminController 那边 @Max(100) 同理.)
  ADMIN_PAGE_SIZES: [20, 50, 100],
  ADMIN_PAGE_SIZE: 20,
}))

import Dashboard from '../Dashboard.vue'
import Users from '../Users.vue'
import Reviews from '../Reviews.vue'
import { getDashboard, getAdminUsers, getAdminReviews } from '../../../api'
import { useUserStore } from '../../../stores/user'

/**
 * 管理端三页改前是同一个毛病: catch 里只有 console.error, 于是接口一挂,
 * 列表保持空数组、页面上显示「暂无数据 / 暂无用户 / 暂无评论」——
 * 把「接口挂了」说成「本来就没有」. 管理端看到空表的第一反应是数据没了.
 *
 * 所以这里钉两件事: 失败时说的是失败(不是「暂无」), 以及有得重试.
 */

const ADMIN = { id: 1, username: 'admin', role: 'ADMIN', token: 'jwt' }

const router = createRouter({
  history: createMemoryHistory(),
  routes: [
    { path: '/', component: { template: '<div />' } },
    { path: '/admin', component: { template: '<div />' } },
    { path: '/admin/users', component: { template: '<div />' } },
    { path: '/admin/reviews', component: { template: '<div />' } },
  ],
})

/**
 * 用户列表的成功响应是**分页信封**, 不是裸数组.
 *
 * ⚠️ 这里若继续用裸数组, `users.value` 会变成一个对象 —— 于是 `users.length` 是
 * undefined、「暂无用户」那条断言会因为**错误的原因**变绿, 而真正的形状错误
 * (信封套错一层)要到线上没人点的时候才发现.
 */
const ADMIN_PAGE_OK = { data: { code: 200, data: { list: [], total: 0, page: 1 } } }

const PAGES = [
  { name: '仪表盘', component: Dashboard, api: () => getDashboard, empty: null,
    ok: { data: { code: 200, data: [] } } },
  { name: '用户管理', component: Users, api: () => getAdminUsers, empty: '暂无用户',
    ok: ADMIN_PAGE_OK },
  // 评论管理也是分页信封了(与用户管理同轮发布). 这里若继续给裸数组,
  // `reviews.value` 会变成一个对象 —— `reviews.length` 是 undefined、
  // 「暂无评论」那条断言因为**错误的原因**变绿, 而真正的形状错误要到线上才发现.
  { name: '评论管理', component: Reviews, api: () => getAdminReviews, empty: '暂无评论',
    ok: ADMIN_PAGE_OK },
]

function mountPage(component) {
  const wrapper = mount(component, { global: { plugins: [router] } })
  return wrapper
}

describe('管理端三页的加载失败', () => {
  beforeEach(async () => {
    vi.clearAllMocks()
    localStorage.clear()
    setActivePinia(createPinia())
    const store = useUserStore()
    store.setUser(ADMIN)
    for (const page of PAGES) page.api().mockResolvedValue(page.ok)
    await router.push('/admin/users')
    await router.isReady()
  })

  for (const page of PAGES) {
    describe(page.name, () => {
      it('接口挂了说「失败」, 不说「暂无」', async () => {
        page.api().mockRejectedValueOnce(new Error('boom'))

        const wrapper = mountPage(page.component)
        await flushPromises()

        expect(wrapper.text()).toContain('加载')
        expect(wrapper.text()).toContain('失败')
        if (page.empty) expect(wrapper.text()).not.toContain(page.empty)
      })

      it('超时与连不上分开说(用户的下一步动作不一样)', async () => {
        const timeout = new Error('timeout')
        timeout.code = 'ECONNABORTED'
        page.api().mockRejectedValueOnce(timeout)

        const wrapper = mountPage(page.component)
        await flushPromises()

        expect(wrapper.text()).toContain('超时')
      })

      it('点「重试」会再请求一次, 成功后正常渲染', async () => {
        page.api().mockRejectedValueOnce(new Error('boom'))
        const wrapper = mountPage(page.component)
        await flushPromises()
        expect(page.api()).toHaveBeenCalledTimes(1)

        const retry = wrapper.findAll('button').find(b => b.text() === '重试')
        expect(retry).toBeTruthy()

        page.api().mockResolvedValue(page.ok)
        await retry.trigger('click')
        await flushPromises()

        expect(page.api()).toHaveBeenCalledTimes(2)
        expect(wrapper.text()).not.toContain('重试')
      })

      it('HTTP 200 但业务码不对, 也不算加载成功', async () => {
        page.api().mockResolvedValueOnce({ data: { code: 500, data: null } })

        const wrapper = mountPage(page.component)
        await flushPromises()

        expect(wrapper.text()).toContain('500')
      })
    })
  }
})
