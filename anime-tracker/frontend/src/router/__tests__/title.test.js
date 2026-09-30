import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import router from '../../router'

/**
 * 标签页标题.
 *
 * 改前整站只有 index.html 里写死的那一条: 从首页点到详情再点进助手, 浏览器标签页、
 * 历史记录、书签**永远是同一句话**, 开三个标签分不清哪个是哪个.
 *
 * 这里用的是**真的那个 router 单例**(与 guard.test.js 同一套做法): 标题是在
 * afterEach 里写的, 换个自己造的 router 就测不到这条链子了.
 */
describe('标签页标题', () => {
  beforeEach(async () => {
    // vue-router 每次导航都会调 window.scrollTo 放滚动位置, jsdom 没实现它 ——
    // 不挡掉的话每条用例都会往输出里泼一串 "Not implemented"
    vi.stubGlobal('scrollTo', vi.fn())
    localStorage.clear()
    // 回到一个中立起点. 不这么做的话, push 到当前已在的路径会被当成重复导航跳过,
    // afterEach 根本不跑 —— 用例会假绿
    await router.push('/')
    expect(router.currentRoute.value.path).toBe('/')
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('首页', () => {
    expect(document.title).toBe('首页 · AniTrack')
  })

  it('换一页换一个标题', async () => {
    await router.push('/search')
    expect(document.title).toBe('搜索 · AniTrack')
  })

  it('带参数的详情页标题不带 id', async () => {
    await router.push('/anime/123')
    expect(document.title).toBe('番剧详情 · AniTrack')
  })

  it('助手页', async () => {
    await router.push('/assistant')
    expect(document.title).toBe('AI 助手 · AniTrack')
  })

  it('分类页', async () => {
    // 从首页搬出来的独立页, 导航栏是它唯一的入口 —— 它要是没挂上路由或漏了
    // meta.title, 用户点进去会看到站名兜底的那个标题, 而页面本身照常渲染
    await router.push('/tags')
    expect(document.title).toBe('分类浏览 · AniTrack')
  })

  it('被守卫重定向掉的那一页, 标题不会先闪一下', async () => {
    // 未登录访问 /profile: 真的进去的是登录页. 标题必须是「登录」——
    // 用 beforeEach 改名的话这里会先写成「个人中心」再被覆盖, 标签页上闪一下
    // 一个没去成的页面名. 这也是 afterEach 那边 rememberPath 用的同一个理由
    await router.push('/profile')

    expect(router.currentRoute.value.path).toBe('/login')
    expect(document.title).toBe('登录 · AniTrack')
  })

  describe('路由没写 meta.title 时', () => {
    afterEach(() => {
      if (router.hasRoute('Bare')) router.removeRoute('Bare')
    })

    it('退回站点名, 而不是拼一个只有后缀的空标题', async () => {
      // 「 · AniTrack」比不写还难认 —— 兜底要走"站点名"这条, 不是模板串里
      // 那个 title 为 undefined 的分支
      router.addRoute({ path: '/__bare', name: 'Bare', component: { template: '<div />' } })
      await router.push('/__bare')

      expect(document.title).toBe('AniTrack - 动漫追番')
    })
  })
})
