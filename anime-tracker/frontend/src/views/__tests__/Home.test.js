import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'
import { createPinia, setActivePinia } from 'pinia'

vi.mock('../../api', () => ({
  getRanking: vi.fn(() => Promise.resolve({ data: { data: [] } })),
  getCalendar: vi.fn(() => Promise.resolve({ data: { data: [] } })),
  // 分类浏览整块搬去了 /tags, Home 已经不再 import `getTags` —— 这里**故意**
  // 留着它当哨兵: 一个"不该被调用"的桩, 才能断言它没被调用. 工厂里删掉它的话
  // 那条断言就无从写起(而 Home 一旦把它 import 回来, 构建也不会报错).
  getTags: vi.fn(() => Promise.resolve({ data: { data: [] } })),
  // ⚠️ 这个工厂是**整体替换** ../../api: 漏列一个 Home 真的用到的导出,
  // 调用点会拿到 undefined 并在运行时炸(而报错位置看起来与那个导出无关).
  // Home 从「继续看」那一版起就用它了, 所以这里必须有.
  getContinueWatching: vi.fn(() => Promise.resolve({ data: { data: [] } })),
}))

import Home from '../Home.vue'
import { getRanking, getCalendar, getTags, getContinueWatching } from '../../api'
import { homeCache, resetHomeCache } from '../../utils/homeCache'
import { useUserStore } from '../../stores/user'
import { clearStoredUser } from '../../utils/userStorage'

/**
 * 首页缓存的 TTL 只有在缓存对象**活得比组件实例久**的时候才谈得上生效.
 *
 * 所以这里不是断言"缓存了", 而是断言"卸载再挂载之后不再发请求" —— 改前缓存
 * 写在 <script setup> 里, 每个实例一份, 卸载即丢, 这个用例会红.
 */

const router = createRouter({
  history: createMemoryHistory(),
  routes: [
    { path: '/', component: { template: '<div />' } },
    { path: '/anime/:id', component: { template: '<div />' } },
  ],
})

// Home 用 v-reveal (IntersectionObserver), jsdom 没有实现它
class FakeIntersectionObserver {
  observe() {}
  unobserve() {}
  disconnect() {}
}

/**
 * 挂载首页.
 *
 * 从「继续看」那一版起 Home 会读登录态(useUserStore), 所以每一挂都必须有一份
 * **活的 pinia** —— 少了它, Home 里的 useUserStore() 会抛 "no active Pinia",
 * 而这条错误会让**这个文件里所有**用例一起红, 包括那些与登录毫无关系的.
 *
 * storage 也要先清干净: store 的初值来自 loadStoredUser(), 上一条用例 setUser
 * 写进去的那份会让这一条本该「匿名」的挂载看起来像已登录 —— 而 vue-test-utils
 * 的 global.stubs 之类都拦不住它, 因为读的是真 localStorage.
 *
 * token 给了就顺带把登录态塞进 store(UserStore 的 loggedIn 要求非空 token).
 */
function mountHome({ token = null } = {}) {
  clearStoredUser()
  const pinia = createPinia()
  setActivePinia(pinia)
  if (token) useUserStore().setUser({ username: 'me', token })
  return mount(Home, { global: { plugins: [router, pinia] } })
}

describe('首页缓存', () => {
  beforeEach(() => {
    resetHomeCache()
    vi.clearAllMocks()
    vi.stubGlobal('IntersectionObserver', FakeIntersectionObserver)
    getRanking.mockResolvedValue({ data: { data: [] } })
  })

  it('第二次进入首页直接用缓存, 不再发请求', async () => {
    const first = mountHome()
    await flushPromises()
    // 核心数据是两个排行接口(rank + date)
    expect(getRanking).toHaveBeenCalledTimes(2)
    first.unmount()

    const second = mountHome()
    await flushPromises()

    expect(getRanking).toHaveBeenCalledTimes(2)
    second.unmount()
  })

  it('首页不再请求标签, 缓存载荷里也没有它', async () => {
    const wrapper = mountHome()
    await flushPromises()

    // 分类区搬去 /tags 之后, 首屏少一个请求. 这条断言是这条改动**唯一**的守卫:
    // 仓内没有 homeCache.test.js, 载荷收窄这件事没有别的地方看着
    expect(getTags).not.toHaveBeenCalled()

    // 两半必须同时改: 写缓存的那半若还塞着 tags, 读缓存的那半会拿到它;
    // 只改一侧就是 undefined 静默流传. 钉形状比钉某一侧安全
    expect(Object.keys(homeCache.data).sort()).toEqual(['hero', 'popular', 'recent', 'today'])

    wrapper.unmount()
  })

  it('过了 5 分钟 TTL 就重新请求', async () => {
    // 只伪造 Date, 不伪造定时器 —— flushPromises 自己要用定时器
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.setSystemTime(new Date('2026-09-29T10:00:00Z'))

    const first = mountHome()
    await flushPromises()
    first.unmount()
    expect(getRanking).toHaveBeenCalledTimes(2)

    vi.setSystemTime(new Date('2026-09-29T10:05:01Z'))
    const second = mountHome()
    await flushPromises()

    expect(getRanking).toHaveBeenCalledTimes(4)
    second.unmount()
    vi.useRealTimers()
  })

  it('加载失败不写缓存: 再进来会真的重发请求, 并显示错误态', async () => {
    getRanking.mockRejectedValueOnce(new Error('boom'))

    const first = mountHome()
    await flushPromises()

    expect(first.text()).toContain('加载首页失败')
    first.unmount()

    const second = mountHome()
    await flushPromises()

    // 失败的那次没写缓存, 所以还会有新请求发出去(而不是命中一份空缓存)
    expect(getRanking.mock.calls.length).toBeGreaterThan(2)
    second.unmount()
  })
})

/**
 * 首页三类入口的键盘可达性.
 *
 * 改前 today-card / hs-card 全都只有 @click: 它们是 div 和 span, tab 键直接
 * 跳过去, 读屏软件也不说这是能按的东西. 鼠标用户永远看不出这个问题, 所以只能
 * 靠断言把 role / tabindex / 按键这三件事钉住.
 *
 * 题外话: interactions.css 里那份 :focus-visible 名单**早就**把 .anime-card、
 * .tag-chip 这些类写进去了 —— 也就是说当初是打算给它们做焦点态的,
 * 只是一直没有元素能被 focus, 那条规则从写下那天起就没匹配过任何东西.
 * (.tag-chip 那一半现在归 Tags.vue 管了, 见 Tags.test.js.)
 */

const TODAY_ITEM = { id: 501, nameCn: '今日番', name: 'Today', images: { medium: 'a.jpg' } }
const HS_ITEM = { id: 502, nameCn: '热门番', name: 'Hot', images: { large: 'b.jpg' } }

/**
 * 日历假数据必须**独立于被测代码**推导.
 *
 * 改前这里是 `WEEKDAYS[new Date().getDay()]`, 而 WEEKDAYS 就是 Home.vue 里那个
 * 数组 —— 断言与被测代码共用同一个常量, 于是把两边一起写错. 真接口回的是
 * `{cn:'星期三', id:3}`, 假数据回的是 `{cn:'周三'}`, 在"匹配得上"这个意义上
 * 长得一模一样, 所以「今日放送」整个区块消失了一整轮, 测试却全绿.
 *
 * 现在按真接口的形状独立写一遍: cn 是「星期X」(不是「周X」), id 是 1(周一)
 * 到 7(周日) 的**字符串**(后端把它收成了 Map<String,String>, Jackson 会把 JSON
 * 里的数字强制转成字符串).
 */
const CN_DIGIT = ['日', '一', '二', '三', '四', '五', '六']
const EN_DOW = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat']
function todayCalendarDay(items) {
  const dow = new Date().getDay()          // 0=周日 .. 6=周六
  const id = dow === 0 ? 7 : dow           // Bangumi: 1=周一 .. 7=周日
  return { weekday: { en: EN_DOW[dow], cn: `星期${CN_DIGIT[dow]}`, ja: 'x', id: String(id) }, items }
}

/** 日历是按"今天星期几"取的, 所以假数据也得挂在今天那一格上 */
function calendarForToday(items) {
  return { data: { data: [todayCalendarDay(items)] } }
}

describe('首页卡片的键盘操作', () => {
  beforeEach(async () => {
    resetHomeCache()
    vi.clearAllMocks()
    vi.stubGlobal('IntersectionObserver', FakeIntersectionObserver)
    getRanking.mockResolvedValue({ data: { data: [HS_ITEM] } })
    getCalendar.mockResolvedValue(calendarForToday([TODAY_ITEM]))

    await router.push('/')
    await router.isReady()
  })

  it('今日放送: 回车和空格都能打开详情', async () => {
    const wrapper = mountHome()
    await flushPromises()

    const card = wrapper.find('.today-card')
    expect(card.attributes('role')).toBe('button')
    expect(card.attributes('tabindex')).toBe('0')

    await card.trigger('keydown.enter')
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/anime/501')

    // role=button 的约定是回车和空格都触发. 只测回车的话,
    // "按空格没反应"会成为一个只有键盘用户才会撞上的 bug
    await router.push('/')
    await wrapper.find('.today-card').trigger('keydown.space')
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/anime/501')
  })

  it('横向滚动区的卡片同样是可选中的按钮', async () => {
    const wrapper = mountHome()
    await flushPromises()

    const card = wrapper.find('.hs-card')
    expect(card.attributes('role')).toBe('button')
    expect(card.attributes('tabindex')).toBe('0')

    await card.trigger('keydown.enter')
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/anime/502')
  })

  it('首页没有分类区了 —— 整块搬去了 /tags', async () => {
    const wrapper = mountHome()
    await flushPromises()

    // 键盘可达性那几条跟着标签一起搬走了(见 Tags.test.js), 这里留下的是"搬干净了"
    // 本身: 首页**不该**再有任何 chip. 半搬半留的话, 同一个筛选会有两个入口,
    // 而首页那个仍然会把页面拽回顶部
    expect(wrapper.find('.tag-chip').exists()).toBe(false)
    expect(wrapper.find('.tag-filter').exists()).toBe(false)
  })

  it('按空格不会把页面往下滚(默认行为被拦掉了)', async () => {
    const wrapper = mountHome()
    await flushPromises()

    const event = new KeyboardEvent('keydown', { key: ' ', bubbles: true, cancelable: true })
    wrapper.find('.today-card').element.dispatchEvent(event)

    expect(event.defaultPrevented).toBe(true)
  })
})

/**
 * 首页「继续看」.
 *
 * 这块的特殊之处在于它是首页**第一处读登录态**的地方: 一个公开页上长出了一块
 * 因人而异的内容. 所以这里盯的不是样式, 而是四条边界 —— 谁看得见、什么情况下
 * 整块消失、点了去哪、以及它**不在**缓存里.
 */

/** /api/track/continue 的一行. 形状与 /api/track/list 逐字相同(后端共用行构造器) */
const CONTINUE_ROW = {
  id: 1, subjectId: 601, status: 'watching', progress: 5,
  score: null, notes: null, createdAt: '2026-10-01T00:00:00', updatedAt: '2026-10-01T00:00:00',
  animeTitle: '继续看的番', animeCover: 'c.jpg', totalEpisodes: 12,
}

describe('首页「继续看」', () => {
  beforeEach(async () => {
    resetHomeCache()
    vi.clearAllMocks()
    vi.stubGlobal('IntersectionObserver', FakeIntersectionObserver)
    getRanking.mockResolvedValue({ data: { data: [] } })
    getContinueWatching.mockResolvedValue({ data: { data: [CONTINUE_ROW] } })

    await router.push('/')
    await router.isReady()
  })

  it('已登录: 请求一次, 卡片带上标题、进度条与集号', async () => {
    const wrapper = mountHome({ token: 'jwt' })
    await flushPromises()

    expect(getContinueWatching).toHaveBeenCalledTimes(1)
    const card = wrapper.find('.cw-card')
    expect(card.exists()).toBe(true)
    expect(card.text()).toContain('继续看的番')
    // 总集数已知时才补「/ 共 M 集」—— 本地没缓存过那部番时后端根本不发这个键
    expect(card.text()).toContain('第 5 集')
    expect(card.text()).toContain('/ 共 12 集')
    // 5/12 = 41.67 → 42%. 直接读内联样式而不是截图: 这是"条真的按进度画了"的唯一守卫,
    // 只断言"有条"的话, 一条永远 0% 的进度条照样绿
    expect(wrapper.find('.pb-fill').attributes('style')).toContain('width: 42%')

    // 与今日放送/热门那两类卡片一样, 键盘也要能到
    expect(card.attributes('role')).toBe('button')
    expect(card.attributes('tabindex')).toBe('0')

    wrapper.unmount()
  })

  it('匿名: 一次都不请求, 整块也不渲染', async () => {
    const wrapper = mountHome()
    await flushPromises()

    // 首页是公开页. 匿名时连请求都不该发出去 —— 发了的话后端也只会回 401,
    // 而 401 会被全局处理弹到登录页, 于是「打开首页」变成「被要求登录」
    expect(getContinueWatching).not.toHaveBeenCalled()
    expect(wrapper.find('.cw-card').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('继续看')

    wrapper.unmount()
  })

  it('没在看任何番: 请求发了, 但整块不渲染(不留一个空盒子)', async () => {
    getContinueWatching.mockResolvedValue({ data: { data: [] } })

    const wrapper = mountHome({ token: 'jwt' })
    await flushPromises()

    expect(getContinueWatching).toHaveBeenCalledTimes(1)
    expect(wrapper.text()).not.toContain('继续看')
    expect(wrapper.find('.cw-card').exists()).toBe(false)

    wrapper.unmount()
  })

  it('点卡片进那部番的详情页', async () => {
    const wrapper = mountHome({ token: 'jwt' })
    await flushPromises()

    await wrapper.find('.cw-card').trigger('click')
    await flushPromises()

    // 用的是 subjectId 而不是行 id —— 行 id 是追番记录的主键, 拿它拼路由会 404
    expect(router.currentRoute.value.path).toBe('/anime/601')

    wrapper.unmount()
  })

  it('本地没缓存这部番: 标题用编号兜底, 进度条整根不画', async () => {
    getContinueWatching.mockResolvedValue({
      data: { data: [{ id: 2, subjectId: 602, status: 'watching', progress: 3 }] },
    })

    const wrapper = mountHome({ token: 'jwt' })
    await flushPromises()

    expect(wrapper.find('.cw-card').text()).toContain('番剧 #602')
    // 「不知道一共多少集」与「一集都没看」是两件事: 前者不画条, 后者画一条 0% 的
    expect(wrapper.find('.pb-bar').exists()).toBe(false)
    expect(wrapper.find('.cw-card').text()).toContain('第 3 集')
    expect(wrapper.find('.cw-card').text()).not.toContain('共')

    wrapper.unmount()
  })

  /**
   * 这一条钉的是一个真实根因, 不是边界情况。
   *
   * `anime.total_episodes` 来自 Bangumi 的 `total_episodes`, 而上游对绝大多数条目
   * **填的就是 0**(2026-10-02 实测 29379 条里 29322 条). 改前这里直接拿它当分母, 于是
   * `ProgressBar` 的 `v-if="total > 0"` 恒假 —— 首页「继续看」里九成九的卡片没有进度条,
   * 只有碰巧声明值非 0 的那一两张有, 看上去像"随机坏掉".
   *
   * 现在声明值为 0 时退到本地收齐的条数(后端新带的 `episodeTotal`)。
   */
  it('声明总集数是 0(上游没公布)时, 退到本地收齐的条数, 进度条照画', async () => {
    getContinueWatching.mockResolvedValue({
      data: { data: [{ ...CONTINUE_ROW, totalEpisodes: 0, episodeTotal: 12 }] },
    })

    const wrapper = mountHome({ token: 'jwt' })
    await flushPromises()

    expect(wrapper.find('.pb-bar').exists()).toBe(true)
    expect(wrapper.find('.pb-fill').attributes('style')).toContain('width: 42%')
    expect(wrapper.find('.cw-card').text()).toContain('/ 共 12 集')

    wrapper.unmount()
  })

  it('声明总集数有值时仍然用它, 不被本地条数压小', async () => {
    // 犬夜叉这种: 声明 181, 本地真收齐 167。本地优先的话这里会显示「/ 共 167 集」
    getContinueWatching.mockResolvedValue({
      data: { data: [{ ...CONTINUE_ROW, progress: 5, totalEpisodes: 181, episodeTotal: 167 }] },
    })

    const wrapper = mountHome({ token: 'jwt' })
    await flushPromises()

    expect(wrapper.find('.cw-card').text()).toContain('/ 共 181 集')
    expect(wrapper.find('.cw-card').text()).not.toContain('167')

    wrapper.unmount()
  })

  /**
   * 这一条是「继续看**不写进** homeCache」的守卫, 而且只有第二次挂载才看得见.
   *
   * loadHome() 命中缓存时会在函数开头直接 return. 把继续看的请求挂在那个 return
   * 之后, 表现就是「第一次进首页有、第二次没了」—— 而开发时每次都是刷新页面
   * (缓存也跟着没了), 所以这个 bug 在手上怎么试都是好的.
   *
   * homeCache 是**模块级、不含用户维度**的, 所以它也不能被塞进去: 那样换个账号
   * 登录, 首页显示的是上一个人的进度.
   */
  it('第二次挂载命中热缓存, 继续看仍然重新请求', async () => {
    const first = mountHome({ token: 'jwt' })
    await flushPromises()
    expect(getContinueWatching).toHaveBeenCalledTimes(1)
    first.unmount()

    // 前置条件: 排行榜那次确实写进了缓存, 第二次才会走提前 return 那条路.
    // 少了这个断言, 就算缓存没生效这条用例也会绿 —— 那它就什么都没测
    expect(homeCache.data).toBeTruthy()
    expect(getRanking).toHaveBeenCalledTimes(2)

    const second = mountHome({ token: 'jwt' })
    await flushPromises()

    expect(getRanking).toHaveBeenCalledTimes(2)          // 排行榜: 走的缓存
    expect(getContinueWatching).toHaveBeenCalledTimes(2) // 继续看: 照发不误
    expect(second.find('.cw-card').exists()).toBe(true)

    second.unmount()
  })
})
