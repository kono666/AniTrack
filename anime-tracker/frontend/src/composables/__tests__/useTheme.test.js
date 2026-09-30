import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'

/**
 * 站点明暗主题的单一来源.
 *
 * 这段逻辑改前长在 NavBar.vue 的 <script setup> 里, 而切换入口只有导航栏那个
 * 登录后的下拉 —— 后台不渲染 NavBar 之后, 管理员就既切不了主题也退不了登录.
 * 所以它被抽了出来, 由侧栏和导航栏共用一份.
 *
 * 抽出来之后多出一条**必须**钉住的性质: **惰性初始化**. jsdom 里
 * `window.matchMedia` 是 undefined, 而 NavBar.test.js 是在 beforeEach 里才打桩的
 * (即模块导入**之后**). 一旦有人把它写成模块顶层的 `ref(initialLight())`,
 * 每一个间接导入 NavBar 的测试文件都会在 import 阶段就炸 —— 报出来的还是一句
 * 与真实原因毫无关系的 "matchMedia is not a function". 下面第一条用例就是那道红线.
 *
 * 第二条要注意的是: `light` 是**模块作用域**的(全应用一份, 这正是设计本身),
 * 所以在同一个测试文件里它跨用例共用. 每个用例都先 vi.resetModules() 再动态
 * import 一份新的, 才能各自从中立的起点开始.
 */

/** 拿一份全新的模块实例(模块作用域的 light 回到 null) */
async function freshTheme() {
  vi.resetModules()
  return import('../useTheme')
}

function stubPrefersLight(matches) {
  vi.stubGlobal('matchMedia', () => ({ matches, addEventListener() {}, removeEventListener() {} }))
}

/** theme-color 这个 meta 由 index.html 提供; jsdom 的 head 是空的, 得自己放一个 */
function addThemeMeta() {
  const meta = document.createElement('meta')
  meta.setAttribute('name', 'theme-color')
  meta.setAttribute('content', '#0d0c0b')
  document.head.appendChild(meta)
}

function metaContent() {
  return document.querySelector('meta[name="theme-color"]')?.getAttribute('content')
}

describe('useTheme', () => {
  beforeEach(() => {
    vi.unstubAllGlobals()
    localStorage.clear()
    document.body.className = ''
    document.head.innerHTML = ''
    addThemeMeta()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
    localStorage.clear()
  })

  it('导入这个模块本身不碰 matchMedia', async () => {
    // 显式设成 undefined, 让这条测的是**模块**而不是 jsdom 的实现细节:
    // 将来 jsdom 补上 matchMedia 时, 这条仍然在测同一件事
    vi.stubGlobal('matchMedia', undefined)

    // 写成模块顶层的 ref(initialLight()) 的话, 这一行在 import 阶段就会抛
    const mod = await freshTheme()
    expect(typeof mod.useTheme).toBe('function')
  })

  it('没存过主题时跟系统走, 不替用户选深色', async () => {
    // 「没存过」和「存了 dark」是两件事. 改前把它们当成一件事(默认深色),
    // 于是系统偏好浅色、从没点过主题按钮的人, 每次进站都被按进深色
    stubPrefersLight(true)
    expect((await freshTheme()).useTheme().light.value).toBe(true)

    stubPrefersLight(false)
    expect((await freshTheme()).useTheme().light.value).toBe(false)
  })

  it('存过就按存的来, 系统偏好让位', async () => {
    stubPrefersLight(true)
    localStorage.setItem('theme', 'dark')

    expect((await freshTheme()).useTheme().light.value).toBe(false)
  })

  it('切换一次同时改内存、body 类、地址栏色和存储', async () => {
    stubPrefersLight(false) // 起点: 深色
    const { useTheme } = await freshTheme()
    const { light, toggle } = useTheme()
    expect(light.value).toBe(false)

    toggle()
    expect(light.value).toBe(true)
    expect(document.body.classList.contains('light')).toBe(true)
    expect(metaContent()).toBe('#fcf1f0')
    expect(localStorage.getItem('theme')).toBe('light')

    toggle()
    expect(light.value).toBe(false)
    expect(document.body.classList.contains('light')).toBe(false)
    expect(metaContent()).toBe('#0d0c0b')
    expect(localStorage.getItem('theme')).toBe('dark')
  })

  it('两处调用拿到的是同一份状态', async () => {
    // 侧栏与导航栏各调一次 useTheme(). 两个 ref 不是同一个对象的话,
    // 在一处切换、另一处那个按钮的图标和文案就会留在旧值上 —— 而这正是
    // 「后台切了主题, 返回首页时导航栏还是旧的」那种看起来像缓存的现象
    stubPrefersLight(false)
    const { useTheme } = await freshTheme()
    const a = useTheme()
    const b = useTheme()

    expect(a.light).toBe(b.light)

    a.toggle()
    expect(b.light.value).toBe(true)
  })

  it('存储不可用时切换照常, 只是记不住', async () => {
    // 隐私模式 / 站点数据被禁用时 localStorage 会直接抛. 改前 toggle 里那两行
    // 是裸的 —— 点一下主题按钮就把异常抛进事件循环, 而按钮看起来毫无反应
    stubPrefersLight(false)
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => { throw new Error('denied') })
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('denied') })

    const { useTheme } = await freshTheme()
    const { light, toggle } = useTheme()

    // 读不到就落到系统偏好上, 而不是崩掉
    expect(light.value).toBe(false)
    expect(() => toggle()).not.toThrow()
    expect(light.value).toBe(true)
    // body 那条不依赖存储, 照样生效 —— 本次会话仍然是切了的
    expect(document.body.classList.contains('light')).toBe(true)
  })
})
