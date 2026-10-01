import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'

vi.mock('../../../api', () => ({
  // 整体替换模块, 所以**每一个**用到的导出都得列上 —— 漏一个在导入侧就是
  // undefined, 组件在 setup 里对它调一次就整页落到错误态, 而看起来像"功能没做"
  getAdminUserDetail: vi.fn(),
}))

import UserDetail from '../UserDetail.vue'
import { getAdminUserDetail } from '../../../api'

/**
 * 用户详情页: 四态 + 三个小列表.
 *
 * 这一页最要紧的一条是 **404 与「加载失败」是两种界面** —— 混成一种就会出现
 * "一个点了必然再失败的重试按钮". 所以下面有四条用例是专门围着这个分家写的.
 */

function makeRouter() {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: { template: '<div />' } },
      { path: '/admin/users', component: { template: '<div />' } },
      { path: '/admin/users/:id', component: UserDetail },
    ],
  })
}

let router = null
let mounted = []

/** 服务端的形状: { data: { code, data } } */
function ok(payload) {
  return { data: { code: 200, data: payload } }
}

/** 一个内容齐全的详情. 各用例只覆盖自己关心的那几个键 */
function detail(extra = {}) {
  return {
    id: 3,
    username: 'alice',
    email: 'a@example.com',
    avatar: null,
    role: 'USER',
    status: 'ACTIVE',
    createdAt: '2026-01-01T08:00:00',
    lastLoginAt: '2026-09-30T12:34:00',
    locked: false,
    lockedUntil: null,
    counts: { trackings: 0, reviewsAlive: 0, reviewsRemoved: 0, episodesWatched: 0 },
    trackings: [],
    reviews: [],
    actions: [],
    ...extra,
  }
}

async function mountAt(fullPath) {
  router = makeRouter()
  await router.push('/')
  await router.isReady()
  await router.push(fullPath)
  const wrapper = mount(UserDetail, { global: { plugins: [router] } })
  mounted.push(wrapper)
  await flushPromises()
  return wrapper
}

afterEach(() => {
  mounted.forEach((w) => w.unmount())
  mounted = []
})

const buttonWith = (w, text) => w.findAll('button').find((b) => b.text() === text)

describe('用户详情: 四态', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('请求还在飞的时候显示加载中, 不显示任何"没有数据"的话', async () => {
    let resolve
    getAdminUserDetail.mockReturnValue(new Promise((res) => { resolve = res }))
    const wrapper = await mountAt('/admin/users/3')

    expect(wrapper.find('.loading').exists()).toBe(true)
    // "还在加载"与"这个人的列表是空的"必须分得开, 否则网慢的时候用户会以为
    // 这个号什么都没干过
    expect(wrapper.text()).not.toContain('没有追番记录')
    expect(wrapper.text()).not.toContain('用户不存在')

    resolve(ok(detail()))
    await flushPromises()
  })

  it('拿到了就把账号事实、四个计数、三个列表都铺出来', async () => {
    getAdminUserDetail.mockResolvedValue(ok(detail({
      counts: { trackings: 12, reviewsAlive: 4, reviewsRemoved: 1, episodesWatched: 37 },
      trackings: [{ subjectId: 123, animeTitle: '进击的巨人', status: 'WATCHING', progress: 5, updatedAt: '2026-09-01T00:00:00' }],
      reviews: [{ id: 9, subjectId: 123, animeTitle: '进击的巨人', rating: 8, content: '不错', deletedAt: null, createdAt: '2026-09-02T00:00:00' }],
      actions: [{ id: 77, action: 'USER_BAN', actorName: 'admin', detail: '封了 3 天', createdAt: '2026-09-03T00:00:00' }],
    })))
    const wrapper = await mountAt('/admin/users/3')

    expect(getAdminUserDetail).toHaveBeenCalledWith('3')
    expect(wrapper.text()).toContain('alice')
    expect(wrapper.text()).toContain('a@example.com')

    const counts = wrapper.findAll('.count-box').map((b) => b.text())
    expect(counts).toHaveLength(4)
    expect(counts[0]).toContain('12')
    expect(counts[1]).toContain('4')
    expect(counts[2]).toContain('1')
    expect(counts[3]).toContain('37')

    expect(wrapper.text()).toContain('进击的巨人')
    // action 码显示成人话, 与 Audit.vue 同一份口径
    expect(wrapper.text()).toContain('封禁')
    expect(wrapper.text()).not.toContain('USER_BAN')
  })

  it('404 说「用户不存在」并给回列表的路, 不给重试', async () => {
    getAdminUserDetail.mockRejectedValue({ response: { status: 404 } })
    const wrapper = await mountAt('/admin/users/999')

    expect(wrapper.text()).toContain('用户不存在')
    // 这一条是整页的重点: 不存在的用户配一个重试按钮, 就是给管理员一个点了
    // 必然再失败、且失败原因永远不变的按钮
    expect(buttonWith(wrapper, '重试')).toBeFalsy()
    // 返回列表是这一页唯一的出口, 而在"这个人不存在"的时候它是**唯一**能点的东西 ——
    // 它是一个 <router-link>(永远是链接, 不是按钮), 四种状态里都得在
    expect(wrapper.find('.back-link').exists()).toBe(true)
    expect(wrapper.text()).not.toContain('加载用户详情失败')
  })

  it('500 说清楚是失败并给重试, 不说「用户不存在」', async () => {
    getAdminUserDetail.mockRejectedValue({ response: { status: 500 } })
    const wrapper = await mountAt('/admin/users/3')

    expect(wrapper.text()).toContain('加载用户详情失败：服务端返回 500')
    // 服务端挂了却说"这个用户不存在", 性质上比显示"加载失败"更糟: 管理员会
    // 据此以为账号被删了
    expect(wrapper.text()).not.toContain('用户不存在')
    // 服务端出问题才给重试 —— 与上一条合起来才是"两种界面"的完整断言
    expect(buttonWith(wrapper, '重试')).toBeTruthy()
    expect(wrapper.find('.back-link').exists()).toBe(true)
  })

  it('点重试再发一次请求', async () => {
    getAdminUserDetail.mockRejectedValueOnce({ response: { status: 500 } })
    const wrapper = await mountAt('/admin/users/3')
    expect(getAdminUserDetail).toHaveBeenCalledTimes(1)

    getAdminUserDetail.mockResolvedValue(ok(detail({ username: '第二次' })))
    await buttonWith(wrapper, '重试').trigger('click')
    await flushPromises()

    expect(getAdminUserDetail).toHaveBeenCalledTimes(2)
    expect(wrapper.text()).toContain('第二次')
  })

  it('返回用户列表真的跳到列表页', async () => {
    getAdminUserDetail.mockRejectedValue({ response: { status: 404 } })
    const wrapper = await mountAt('/admin/users/999')

    await buttonWith(wrapper, '返回用户列表').trigger('click')
    await flushPromises()

    expect(router.currentRoute.value.path).toBe('/admin/users')
  })

  it('code 不是 200 时走错误态, 不当成"没有这个用户"', async () => {
    // 后端在"业务上失败但 HTTP 200"时给的是这个形状(信封里的 code).
    // 当成 404 的话, 鉴权/参数一类的毛病会被显示成"这个人不存在"
    getAdminUserDetail.mockResolvedValue({ data: { code: 403, message: '没有权限' } })
    const wrapper = await mountAt('/admin/users/3')

    expect(wrapper.text()).toContain('服务端返回 403')
    expect(wrapper.text()).not.toContain('用户不存在')
  })
})

describe('用户详情: 非法 id', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  /**
   * 这一组钉的是"**发请求之前**就判 id".
   *
   * 直接把 route.params.id 拼进 URL 的话, 手输 /admin/users/abc 会带着 abc 发出去、
   * 后端回 400, 界面上变成"加载失败 + 重试" —— 而那个重试按钮点多少次都是 400.
   * 正确答案是"这个用户不存在", 出口是回列表.
   */
  it('非数字 id 一个请求都不发, 直接说没有这个用户', async () => {
    const wrapper = await mountAt('/admin/users/abc')

    expect(getAdminUserDetail).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('没有这个用户')
    expect(wrapper.text()).toContain('abc')
    expect(buttonWith(wrapper, '重试')).toBeFalsy()
    expect(buttonWith(wrapper, '返回用户列表')).toBeTruthy()
  })

  it.each(['0', '-1', '1.5', '1e3', ' 3', '3a'])('%s 也不算合法 id', async (bad) => {
    // 这几个后端都会 400(Long 绑不上), 而它们**看起来都像数字** ——
    // 只做 Number.isNaN 判断的话, -1 / 1.5 / 1e3 会一个个漏过去
    await mountAt(`/admin/users/${encodeURIComponent(bad)}`)

    expect(getAdminUserDetail).not.toHaveBeenCalled()
  })
})

describe('用户详情: 三个小列表', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('三个列表各自有自己的空态文案', async () => {
    getAdminUserDetail.mockResolvedValue(ok(detail()))
    const wrapper = await mountAt('/admin/users/3')

    // 三处共用一个「暂无数据」的话, "这个号没追番"与"服务端没发这个列表"就
    // 分不出来了 —— 而后者正是接口改坏时的样子
    expect(wrapper.text()).toContain('没有追番记录')
    expect(wrapper.text()).toContain('没有评论')
    expect(wrapper.text()).toContain('没有对这个账号的管理操作')
  })

  it('列表非空时不显示空态', async () => {
    getAdminUserDetail.mockResolvedValue(ok(detail({
      trackings: [{ subjectId: 1, animeTitle: 'A', status: 'WATCHING', progress: 1, updatedAt: '2026-09-01T00:00:00' }],
    })))
    const wrapper = await mountAt('/admin/users/3')

    expect(wrapper.text()).not.toContain('没有追番记录')
    expect(wrapper.text()).toContain('没有评论')
  })

  /**
   * 被移除的评论**照样**出现在这个列表里(后端刻意不按 deletedAt 过滤), 靠
   * 「已移除」徽章区分. 去掉徽章的话, 管理员刚移除的那条会与在架的混在一起,
   * 他会以为自己点错了; 而计数上的「已移除 1」与列表对不上时, 两边看着都像 bug.
   */
  it('被移除的评论留在列表里, 并带「已移除」徽章', async () => {
    getAdminUserDetail.mockResolvedValue(ok(detail({
      counts: { trackings: 0, reviewsAlive: 1, reviewsRemoved: 1, episodesWatched: 0 },
      reviews: [
        { id: 9, subjectId: 1, animeTitle: 'A', rating: 8, content: '在架的', deletedAt: null, createdAt: '2026-09-01T00:00:00' },
        { id: 10, subjectId: 2, animeTitle: 'B', rating: 6, content: '被移除的', deletedAt: '2026-09-05T00:00:00', createdAt: '2026-09-02T00:00:00' },
      ],
    })))
    const wrapper = await mountAt('/admin/users/3')

    const rows = wrapper.findAll('.mini-row')
    expect(rows).toHaveLength(2)
    expect(wrapper.text()).toContain('被移除的')

    const badges = wrapper.findAll('.status-removed')
    expect(badges).toHaveLength(1)
    expect(badges[0].text()).toBe('已移除')
    // 徽章只跟那一行走: 两条都打上的话, 徽章就不再区分任何东西
    expect(rows.find((r) => r.text().includes('在架的')).find('.status-removed').exists()).toBe(false)
  })

  it('本地没缓存过那部番时退化成「番剧 #id」, 不编一个假名字', async () => {
    getAdminUserDetail.mockResolvedValue(ok(detail({
      trackings: [{ subjectId: 656083, animeTitle: null, status: 'WATCHING', progress: 1, updatedAt: '2026-09-01T00:00:00' }],
      reviews: [{ id: 9, subjectId: 656084, animeTitle: null, rating: 8, content: 'x', deletedAt: null, createdAt: '2026-09-01T00:00:00' }],
    })))
    const wrapper = await mountAt('/admin/users/3')

    // 「未知作品」那种兜底会让几条不同 subjectId 的记录挤在同一个假名字下面,
    // 而"这部番还没进本地库"本身是真信息
    expect(wrapper.text()).toContain('番剧 #656083')
    expect(wrapper.text()).toContain('番剧 #656084')
    expect(wrapper.text()).not.toContain('未知作品')
  })

  it('认不出来的 action 显示原样的码, 不是一片空白', async () => {
    getAdminUserDetail.mockResolvedValue(ok(detail({
      actions: [{ id: 1, action: 'USER_IMPERSONATE', actorName: 'admin', detail: null, createdAt: '2026-09-01T00:00:00' }],
    })))
    const wrapper = await mountAt('/admin/users/3')

    expect(wrapper.text()).toContain('USER_IMPERSONATE')
    expect(wrapper.text()).toContain('（无详情）')
  })

  it('最近登录为空时说「从未登录」, 不是留一个空格', async () => {
    getAdminUserDetail.mockResolvedValue(ok(detail({ lastLoginAt: null })))
    const wrapper = await mountAt('/admin/users/3')

    // null 与"服务端没发这个键"必须分得开: 「从未登录」是这一列的信息,
    // 而空着看起来像这一页坏了
    expect(wrapper.text()).toContain('从未登录')
  })
})

describe('用户详情: 徽章与只读边界', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('locked 为真时才出现「已锁定」徽章', async () => {
    getAdminUserDetail.mockResolvedValue(ok(detail({ locked: true, lockedUntil: '2027-01-01T00:00:00' })))
    const wrapper = await mountAt('/admin/users/3')

    expect(wrapper.text()).toContain('已锁定')
    expect(wrapper.text()).toContain('锁定至')
  })

  it('locked 为假时连「锁定至」那一行都不出现', async () => {
    getAdminUserDetail.mockResolvedValue(ok(detail({ locked: false, lockedUntil: null })))
    const wrapper = await mountAt('/admin/users/3')

    expect(wrapper.text()).not.toContain('已锁定')
    expect(wrapper.text()).not.toContain('锁定至')
  })

  it('锁定状态由服务端说了算, 前端不拿 lockedUntil 自己比时间', async () => {
    // locked=false 但 lockedUntil 是个**过去**的时间(解锁后的历史值, 后端照样
    // 会发过来). 前端若自己比时间, 这里就会多出一个「已锁定」徽章, 而列表页
    // 那一行说的是"没锁" —— 同一个账号在两个页面上给出相反答复
    getAdminUserDetail.mockResolvedValue(ok(detail({ locked: false, lockedUntil: '2020-01-01T00:00:00' })))
    const wrapper = await mountAt('/admin/users/3')

    expect(wrapper.text()).not.toContain('已锁定')
  })

  it('被禁用(且锁定)的用户照样打得开 —— 这是读操作, 不该继承写入限制', async () => {
    getAdminUserDetail.mockResolvedValue(ok(detail({ status: 'DISABLED' })))
    const wrapper = await mountAt('/admin/users/3')

    expect(wrapper.text()).toContain('已禁用')
    expect(wrapper.text()).toContain('alice')
  })

  /**
   * 这一页刻意是**只读**的: 那四个破坏性动作(封禁/解锁/改角色/重置密码)仍然
   * 只在列表页那一行上做. 那要配 confirm + toast + 整页刷新, 等于把 Users.vue
   * 抄一遍; 而"动作之后这一页上的计数该怎么变"又是另一摊事.
   *
   * 这条用例是那条边界的守卫: 哪天有人"顺手"把四个按钮抄过来, 它会红.
   */
  it('身份换不出来 —— 这一页不放四个破坏性动作', async () => {
    getAdminUserDetail.mockResolvedValue(ok(detail()))
    const wrapper = await mountAt('/admin/users/3')

    const labels = wrapper.findAll('button').map((b) => b.text())
    for (const forbidden of ['禁用', '启用', '解锁', '重置密码', '设为管理员', '设为普通用户']) {
      expect(labels).not.toContain(forbidden)
    }
  })
})

describe('用户详情: 竞态', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  /** 手写的 deferred: 这一组要精确控制"谁先回来", 不用假定时器 */
  function deferred() {
    let resolve, reject
    const promise = new Promise((res, rej) => { resolve = res; reject = rej })
    return { promise, resolve, reject }
  }

  /**
   * 同一组件实例换 id 时不会重挂载(路由记录是同一条, 只是 params 变了),
   * 所以这一页靠 watch(() => route.params.id, load) 重新取数.
   * 没有它, 症状是"地址变了, 页面还是上一个人" —— 而看起来一切正常.
   */
  it('换 id 会重新取数', async () => {
    getAdminUserDetail.mockResolvedValue(ok(detail({ username: '第一个' })))
    const wrapper = await mountAt('/admin/users/3')
    expect(wrapper.text()).toContain('第一个')

    getAdminUserDetail.mockResolvedValue(ok(detail({ id: 4, username: '第二个' })))
    await router.push('/admin/users/4')
    await flushPromises()

    expect(getAdminUserDetail).toHaveBeenLastCalledWith('4')
    expect(wrapper.text()).toContain('第二个')
    expect(wrapper.text()).not.toContain('第一个')
  })

  it('先发的那次后到, 不能盖掉后发的', async () => {
    getAdminUserDetail.mockResolvedValue(ok(detail()))
    const wrapper = await mountAt('/admin/users/3')

    const slow = deferred()
    const fast = deferred()
    getAdminUserDetail.mockReturnValueOnce(slow.promise).mockReturnValueOnce(fast.promise)

    await router.push('/admin/users/4')
    await flushPromises()
    await router.push('/admin/users/5')
    await flushPromises()

    fast.resolve(ok(detail({ id: 5, username: '较新的那次' })))
    await flushPromises()
    expect(wrapper.text()).toContain('较新的那次')

    slow.resolve(ok(detail({ id: 4, username: '较旧的那次' })))
    await flushPromises()

    expect(wrapper.text()).toContain('较新的那次')
    expect(wrapper.text()).not.toContain('较旧的那次')
  })

  it('过期那次的失败也不能把页面推去错误态', async () => {
    getAdminUserDetail.mockResolvedValue(ok(detail()))
    const wrapper = await mountAt('/admin/users/3')

    const slow = deferred()
    const fast = deferred()
    getAdminUserDetail.mockReturnValueOnce(slow.promise).mockReturnValueOnce(fast.promise)

    await router.push('/admin/users/4')
    await flushPromises()
    await router.push('/admin/users/5')
    await flushPromises()

    fast.resolve(ok(detail({ id: 5, username: '较新的那次' })))
    await flushPromises()

    // 过期分支若写了 state, 用户看到的会是"上一个人加载失败" —— 一次既没有
    // 请求也没有原因的失败
    slow.reject({ response: { status: 500 } })
    await flushPromises()

    expect(wrapper.text()).toContain('较新的那次')
    expect(wrapper.text()).not.toContain('加载用户详情失败')
  })
})
