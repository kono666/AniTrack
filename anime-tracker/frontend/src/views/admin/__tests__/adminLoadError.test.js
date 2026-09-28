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

const PAGES = [
  { name: '仪表盘', component: Dashboard, api: () => getDashboard, empty: null },
  { name: '用户管理', component: Users, api: () => getAdminUsers, empty: '暂无用户' },
  { name: '评论管理', component: Reviews, api: () => getAdminReviews, empty: '暂无评论' },
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
    for (const page of PAGES) page.api().mockResolvedValue({ data: { code: 200, data: [] } })
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

        page.api().mockResolvedValue({ data: { code: 200, data: [] } })
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
