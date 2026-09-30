import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'

vi.mock('../../api', () => ({
  getFiltered: vi.fn(),
  getFilterMeta: vi.fn(),
}))

import Tags from '../Tags.vue'
import { getFiltered, getFilterMeta } from '../../api'

/**
 * 分类浏览页: 左栏六组条件、右栏结果.
 *
 * 这一页此前是一堵 30 个 chip 的标签墙(形态是从首页搬来的), 点下去与「全部」
 * 看不出区别 —— 那 30 个里 29 个都是日本 / TV 这种全站级的标签. 这一轮换成
 * 命名维度 + 封闭词表, 语义是主流平台那套「组内或、组间与」.
 *
 * ⚠️ 挂载过的组件在**文件级 afterEach** 里统一卸载, 不在用例末尾各写一句:
 * 这两组要数"发了几次请求"、还要用 mockReturnValueOnce 排定谁先回来, 而残留组件
 * 的 route watcher 会跟着路由动. 用例中途断言失败时末尾那句 unmount 根本走不到,
 * 于是**一条失败会污染后面每一条**(实测: 三条没卸载干净 → 一次 push 触发四次请求).
 */
const router = createRouter({
  history: createMemoryHistory(),
  routes: [
    { path: '/tags', component: { template: '<div />' } },
    { path: '/anime/:id', component: { template: '<div />' } },
  ],
})

// Tags 用 v-reveal (IntersectionObserver), jsdom 没有实现它
class FakeIntersectionObserver {
  observe() {}
  unobserve() {}
  disconnect() {}
}

const mounted = []
function mountTags() {
  const wrapper = mount(Tags, { global: { plugins: [router] } })
  mounted.push(wrapper)
  return wrapper
}
afterEach(() => {
  while (mounted.length) mounted.pop().unmount()
})

function card(id) {
  return { id, nameCn: `番剧${id}`, images: {}, rating: { score: 8 } }
}
function filteredPage(list, total = 45) {
  return { data: { data: { list, total } } }
}
function metaResponse(years = ['2026', '2025', '2024']) {
  return {
    data: {
      data: {
        years,
        statuses: [
          { value: 'finished', label: '已完结' },
          { value: 'airing', label: '放送中' },
        ],
      },
    },
  }
}
function deferred() {
  let resolve, reject
  const promise = new Promise((res, rej) => { resolve = res; reject = rej })
  return { promise, resolve, reject }
}

/** 某一组(.filter-group)的 DOM. 组的标题就是它唯一的那行 h2 */
function group(wrapper, label) {
  return wrapper.findAll('.filter-group').find((g) => g.find('h2').text() === label)
}
/** 某一组里的某个按钮. 用文字找而不是下标 —— 下标会随折叠/排序变动 */
function optionIn(wrapper, groupLabel, optionLabel) {
  return group(wrapper, groupLabel).findAll('.filter-option').find((b) => b.text() === optionLabel)
}
/** 找得到吗. 断言"没有被折叠藏起来"时用这个 —— optionIn 找不到会返回 undefined,
    直接 .exists() 会变成 TypeError, 而那看起来像"视图坏了"而不是"断言没用对" */
function hasOption(wrapper, groupLabel, optionLabel) {
  return Boolean(optionIn(wrapper, groupLabel, optionLabel))
}
/** 最近一次 /filter 发出去的参数 */
function lastSentParams() {
  return getFiltered.mock.calls[getFiltered.mock.calls.length - 1][0]
}

describe('分类页: 六组条件进 URL', () => {
  beforeEach(async () => {
    vi.clearAllMocks()
    vi.stubGlobal('IntersectionObserver', FakeIntersectionObserver)
    // jsdom 里 Element.prototype.scrollIntoView 是 **undefined**(不是 no-op):
    // 不打桩的话点条件会直接 TypeError, 而它会被组件的 catch 吞掉, 变成
    // "看着绿其实什么都没发生"的假绿
    Element.prototype.scrollIntoView = vi.fn()
    getFilterMeta.mockResolvedValue(metaResponse())
    getFiltered.mockResolvedValue(filteredPage([card(1)]))

    await router.push('/tags')
    await router.isReady()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
  })

  /**
   * 首屏直接是"全部番剧第 1 页", 不是一堵墙.
   *
   * 这条**推翻了** c77 定下的"没选条件就不发请求" —— 那条是给"上墙下果"的旧形态定的:
   * 墙是这一页的主体, 结果要用户点出来. 左右两栏里右栏空着看起来就是坏了.
   */
  it('首访: 发一次不带任何条件参数的 /filter, 右栏直接出结果', async () => {
    const wrapper = mountTags()
    await flushPromises()

    expect(getFiltered).toHaveBeenCalledTimes(1)
    const sent = lastSentParams()
    for (const key of ['genre', 'medium', 'source', 'region', 'year', 'status']) {
      // "没选"必须表现为**参数不存在**, 不是空串 —— 空串会被后端当成一个空名字
      expect(sent[key]).toBeUndefined()
    }
    expect(sent.sort).toBe('date')
    expect(sent.page).toBe(1)
    expect(sent.limit).toBe(24)

    expect(getFilterMeta).toHaveBeenCalledTimes(1)
    expect(wrapper.text()).toContain('番剧1')
    expect(wrapper.text()).toContain('共 45 部')
    // 深链/首访时用户还没做任何动作, 页面不该自己滚一下
    expect(Element.prototype.scrollIntoView).not.toHaveBeenCalled()

    wrapper.unmount()
  })

  /**
   * 组内是「或」: 两个题材按钮各展开成一串标签名, 一起去请求.
   *
   * 展开是有损的 —— 「机甲」在库里对应四个写法(机战/萝卜/机甲/机器人), 少了任何一个
   * 都会漏掉一批番, 而用户没有任何理由知道自己漏了. 所以这条断言盯的是**完整的展开
   * 结果**, 不是"传了非空"。
   */
  it('组内或: 点两个题材 → URL 上是两个 slug, 请求里是两串标签名', async () => {
    const wrapper = mountTags()
    await flushPromises()
    getFiltered.mockClear()

    await optionIn(wrapper, '题材', '机甲').trigger('click')
    await flushPromises()
    await optionIn(wrapper, '题材', '奇幻').trigger('click')
    await flushPromises()

    expect(router.currentRoute.value.query.genre).toBe('mecha,fantasy')
    expect(getFiltered).toHaveBeenCalledTimes(2)
    expect(lastSentParams().genre).toBe('机战,萝卜,机甲,机器人,奇幻,魔幻,玄幻')
    // 再点一次是取消, 不是追加
    await optionIn(wrapper, '题材', '机甲').trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.query.genre).toBe('fantasy')

    wrapper.unmount()
  })

  it('组间与: 题材与地区各选一个, 两个参数在同一次请求里', async () => {
    const wrapper = mountTags()
    await flushPromises()
    getFiltered.mockClear()

    await optionIn(wrapper, '题材', '机甲').trigger('click')
    await flushPromises()
    await optionIn(wrapper, '地区', '日本').trigger('click')
    await flushPromises()

    const sent = lastSentParams()
    expect(sent.genre).toBe('机战,萝卜,机甲,机器人')
    expect(sent.region).toBe('日本,日本动画,日漫')

    wrapper.unmount()
  })

  it('年份单选: 点第二个换掉第一个, 再点一次取消', async () => {
    const wrapper = mountTags()
    await flushPromises()
    getFiltered.mockClear()

    await optionIn(wrapper, '年份', '2025').trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.query.year).toBe('2025')
    expect(lastSentParams().year).toBe('2025')

    await optionIn(wrapper, '年份', '2024').trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.query.year).toBe('2024')

    await optionIn(wrapper, '年份', '2024').trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.query.year).toBeUndefined()
    expect(lastSentParams().year).toBeUndefined()

    wrapper.unmount()
  })

  it('「清除」只在该组有选中时出现, 点了之后 URL 与请求一起干净', async () => {
    const wrapper = mountTags()
    await flushPromises()

    expect(group(wrapper, '题材').findAll('.filter-clear')).toHaveLength(0)

    await optionIn(wrapper, '题材', '机甲').trigger('click')
    await flushPromises()
    await optionIn(wrapper, '题材', '奇幻').trigger('click')
    await flushPromises()
    await optionIn(wrapper, '年份', '2025').trigger('click')
    await flushPromises()

    await group(wrapper, '题材').find('.filter-clear').trigger('click')
    await flushPromises()

    expect(router.currentRoute.value.query.genre).toBeUndefined()
    expect(router.currentRoute.value.query.year).toBe('2025')   // 别的组不受牵连
    expect(lastSentParams().genre).toBeUndefined()
    expect(group(wrapper, '题材').findAll('.filter-clear')).toHaveLength(0)

    wrapper.unmount()
  })

  /**
   * 深链: 分享/收藏出去的链接直接打开, 或者刷新.
   *
   * `year=2020` 刻意**不在** /filter-meta 给的年份档里(那是"近 20 年"), 用来钉住
   * "URL 上的年份是条件, 不是档位" —— 它照样要发给后端, 而不是因为渲染不出按钮
   * 就被丢掉.
   */
  it('深链: URL 上的条件读得回来, 对应的按钮是亮着的', async () => {
    await router.push('/tags?genre=mecha&year=2020&page=2')
    const wrapper = mountTags()
    await flushPromises()

    const sent = lastSentParams()
    expect(sent.genre).toBe('机战,萝卜,机甲,机器人')
    expect(sent.year).toBe('2020')
    expect(sent.page).toBe(2)
    expect(optionIn(wrapper, '题材', '机甲').attributes('aria-pressed')).toBe('true')

    wrapper.unmount()
  })

  it('认不出来的 slug 被丢掉, 不会拿它去请求', async () => {
    // 手改过的 URL. 带着一个查不到的名字去请求, 后端会让整组变成空结果
    // (见 AnimeService.getFilteredPage 对"给了名字却一个都没解析出来"的处理)
    await router.push('/tags?genre=mecha,没这个词')
    const wrapper = mountTags()
    await flushPromises()

    expect(lastSentParams().genre).toBe('机战,萝卜,机甲,机器人')

    wrapper.unmount()
  })

  it('同路径的 query 变化(后退/前进)会被读回来并取那一页', async () => {
    const wrapper = mountTags()
    await flushPromises()
    getFiltered.mockClear()

    await router.push('/tags?genre=mecha&page=2')
    await flushPromises()

    expect(getFiltered).toHaveBeenCalledTimes(1)
    expect(lastSentParams().genre).toBe('机战,萝卜,机甲,机器人')
    expect(lastSentParams().page).toBe(2)

    wrapper.unmount()
  })
})

describe('分类页: 竞态', () => {
  beforeEach(async () => {
    vi.clearAllMocks()
    vi.stubGlobal('IntersectionObserver', FakeIntersectionObserver)
    Element.prototype.scrollIntoView = vi.fn()
    getFilterMeta.mockResolvedValue(metaResponse())
    getFiltered.mockResolvedValue(filteredPage([card(1)]))

    await router.push('/tags?genre=mecha')
    await router.isReady()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
  })

  it('先发的那次后到, 不能盖掉后发的结果', async () => {
    const wrapper = mountTags()
    await flushPromises()

    const slow = deferred()
    const fast = deferred()
    getFiltered.mockReturnValueOnce(slow.promise).mockReturnValueOnce(fast.promise)

    await router.push('/tags?genre=mecha&page=2')
    await flushPromises()
    await router.push('/tags?genre=mecha&page=3')
    await flushPromises()

    fast.resolve(filteredPage([card(33)]))
    await flushPromises()
    expect(wrapper.text()).toContain('番剧33')

    slow.resolve(filteredPage([card(22)]))
    await flushPromises()

    expect(wrapper.text()).toContain('番剧33')
    expect(wrapper.text()).not.toContain('番剧22')

    wrapper.unmount()
  })

  it('过期那一份先回来时转圈不许停 —— 关它的只能是最后一次', async () => {
    const wrapper = mountTags()
    await flushPromises()

    const stale = deferred()
    const fresh = deferred()
    getFiltered.mockReturnValueOnce(stale.promise).mockReturnValueOnce(fresh.promise)

    await router.push('/tags?genre=mecha&page=2')
    await flushPromises()
    await router.push('/tags?genre=mecha&page=3')
    await flushPromises()

    // **先发的先回来**, 而它已经过期 —— 这个顺序是这条用例的全部意义.
    // 过期分支里若顺手写了 loading.value = false, 转圈就在这一刻灭了,
    // 而真正该关它的那次还在飞: 界面从此停在"没有转圈、也还是旧内容"的样子.
    // (反过来先 resolve fresh 的话, 两次都是写 false, 什么都看不出来.)
    stale.resolve(filteredPage([card(22)]))
    await flushPromises()
    expect(wrapper.text()).not.toContain('番剧22')          // 过期结果没写进列表
    expect(wrapper.find('.loading').exists()).toBe(true)     // 转圈还在

    fresh.resolve(filteredPage([card(33)]))
    await flushPromises()
    expect(wrapper.text()).toContain('番剧33')
    expect(wrapper.find('.loading').exists()).toBe(false)    // 这次才是收工

    wrapper.unmount()
  })
})

describe('分类页: 改条件后把结果送去视野', () => {
  beforeEach(async () => {
    vi.clearAllMocks()
    vi.stubGlobal('IntersectionObserver', FakeIntersectionObserver)
    Element.prototype.scrollIntoView = vi.fn()
    getFilterMeta.mockResolvedValue(metaResponse())
    getFiltered.mockResolvedValue(filteredPage([card(1)]))

    await router.push('/tags')
    await router.isReady()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
  })

  /**
   * block 必须是 'start', 这一条**推翻了** c77 定下的 'nearest'.
   *
   * 那一条是在"标签墙在上、结果在下"的页面里定的: 墙只有 4 行, 结果本来就露在墙下面,
   * 'nearest'("已经看得见就不动")让那次调用成为空操作、不会把墙顶出视野 —— 在那里是对的.
   * 左右两栏里结果**就是**目的地: 'nearest' 会判"结果区还看得见"而一动不动, 用户停在
   * 旧内容上, 正是 c77 在详情页修掉的那个毛病.
   */
  it('点条件后滚一次, 用 start', async () => {
    const wrapper = mountTags()
    await flushPromises()
    Element.prototype.scrollIntoView.mockClear()

    await optionIn(wrapper, '题材', '机甲').trigger('click')
    await flushPromises()

    expect(Element.prototype.scrollIntoView).toHaveBeenCalledTimes(1)
    expect(Element.prototype.scrollIntoView).toHaveBeenCalledWith({ block: 'start' })

    wrapper.unmount()
  })

  it('翻页也滚 —— 用户点的就是分页控件, 结果就在脚下', async () => {
    const wrapper = mountTags()
    await flushPromises()
    Element.prototype.scrollIntoView.mockClear()

    const page2 = wrapper.findAll('.pg-btn').find((b) => b.text() === '2')
    await page2.trigger('click')
    await flushPromises()

    expect(Element.prototype.scrollIntoView).toHaveBeenCalledWith({ block: 'start' })

    wrapper.unmount()
  })

  it('带条件深链进来时不滚 —— 刚打开的一页不该自己动', async () => {
    await router.push('/tags?genre=mecha&year=2020')
    const wrapper = mountTags()
    await flushPromises()

    expect(Element.prototype.scrollIntoView).not.toHaveBeenCalled()

    wrapper.unmount()
  })
})

describe('分类页: 结果区与年份/状态各自的错误态', () => {
  beforeEach(async () => {
    vi.clearAllMocks()
    vi.stubGlobal('IntersectionObserver', FakeIntersectionObserver)
    Element.prototype.scrollIntoView = vi.fn()
    getFilterMeta.mockResolvedValue(metaResponse())
    getFiltered.mockResolvedValue(filteredPage([card(1)]))

    // 这一组刻意从**干净**的 URL 进来: 下面既要验"有条件下"的错误态, 也要验
    // "一个条件都没选"时那句话, 提前选上 genre 会把后一条悄悄改成前一条
    await router.push('/tags')
    await router.isReady()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
  })

  it('/filter 挂了: 结果区出错条 + 重试, 而左栏照常', async () => {
    getFiltered.mockRejectedValueOnce(new Error('boom'))
    const wrapper = mountTags()
    await flushPromises()

    expect(wrapper.text()).toContain('加载分类失败')
    // 左栏不该被结果区的错误带走 —— 它是这一页的另一半
    expect(optionIn(wrapper, '题材', '机甲').exists()).toBe(true)
    expect(optionIn(wrapper, '年份', '2025').exists()).toBe(true)
    expect(wrapper.findAll('.filter-retry')).toHaveLength(1)

    wrapper.unmount()
  })

  it('结果区的重试只重发 /filter, 不重发 /filter-meta', async () => {
    getFiltered.mockRejectedValueOnce(new Error('boom'))
    const wrapper = mountTags()
    await flushPromises()
    getFiltered.mockClear()
    getFilterMeta.mockClear()

    await wrapper.find('.filter-retry').trigger('click')
    await flushPromises()

    expect(getFiltered).toHaveBeenCalledTimes(1)
    expect(getFilterMeta).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('番剧1')

    wrapper.unmount()
  })

  /**
   * /filter-meta 只喂年份与状态两组, 结果不依赖它.
   *
   * 两者挤在同一个 try 里的话, 它一挂整页就没有内容了 —— 而"年份下拉拿不到值"
   * 完全不该让右栏也空掉.
   */
  it('/filter-meta 挂了: 只让年份/状态两组出错, 结果区照常出卡片', async () => {
    getFilterMeta.mockRejectedValueOnce(new Error('boom'))
    const wrapper = mountTags()
    await flushPromises()

    expect(wrapper.text()).toContain('加载筛选条件失败')
    expect(wrapper.text()).toContain('番剧1')
    // 两组各一条, 因为两组的值都来自这同一个接口
    expect(wrapper.findAll('.filter-retry')).toHaveLength(2)

    wrapper.unmount()
  })

  it('年份那一组的重试只重发 /filter-meta', async () => {
    getFilterMeta.mockRejectedValueOnce(new Error('boom'))
    const wrapper = mountTags()
    await flushPromises()
    getFiltered.mockClear()
    getFilterMeta.mockClear()

    await wrapper.find('.filter-retry').trigger('click')
    await flushPromises()

    expect(getFilterMeta).toHaveBeenCalledTimes(1)
    expect(getFiltered).not.toHaveBeenCalled()
    expect(optionIn(wrapper, '年份', '2025').exists()).toBe(true)

    wrapper.unmount()
  })

  it('结果为空时给一句空态, 并把选中的条件说出来', async () => {
    getFiltered.mockResolvedValue(filteredPage([], 0))
    const wrapper = mountTags()
    await flushPromises()

    await optionIn(wrapper, '题材', '机甲').trigger('click')
    await flushPromises()

    expect(wrapper.find('.empty-state').exists()).toBe(true)
    // 屏幕上没有 mecha 这个词 —— 文案要说用户点的那个中文词
    expect(wrapper.text()).toContain('「机甲」暂无作品')

    wrapper.unmount()
  })

  it('一个条件都没选又没有结果时, 空态说的是"站里还没有可浏览的作品"', async () => {
    getFiltered.mockResolvedValue(filteredPage([], 0))
    const wrapper = mountTags()
    await flushPromises()

    expect(wrapper.text()).toContain('站里还没有可浏览的作品')

    wrapper.unmount()
  })
})

describe('分类页: 数量显示与题材折叠', () => {
  beforeEach(async () => {
    vi.clearAllMocks()
    vi.stubGlobal('IntersectionObserver', FakeIntersectionObserver)
    Element.prototype.scrollIntoView = vi.fn()
    getFilterMeta.mockResolvedValue(metaResponse())
    getFiltered.mockResolvedValue(filteredPage([card(1)]))

    await router.push('/tags')
    await router.isReady()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
  })

  it('一个数字都不显示 —— 旧的 ({{ tag.count }}) 已经删掉', async () => {
    const wrapper = mountTags()
    await flushPromises()

    // 旧墙上每个 chip 后面挂着 "(11712)" 这种数字. 它既不参与筛选, 又抢走注意力,
    // 而它落的正是「日本 11712 / TV 11489」那种全站级的无意义标签
    expect(wrapper.find('.tag-count').exists()).toBe(false)

    wrapper.unmount()
  })

  it('题材默认只摆 12 个, 点「展开全部」放完', async () => {
    const wrapper = mountTags()
    await flushPromises()

    expect(hasOption(wrapper, '题材', '机甲')).toBe(true)
    expect(hasOption(wrapper, '题材', '恐怖')).toBe(false)   // 第 32 个, 收在折叠后面
    expect(group(wrapper, '题材').findAll('.filter-option')).toHaveLength(13)  // 12 + 展开全部

    await group(wrapper, '题材').find('.filter-more').trigger('click')
    await flushPromises()

    expect(hasOption(wrapper, '题材', '恐怖')).toBe(true)
    expect(group(wrapper, '题材').findAll('.filter-option')).toHaveLength(32)

    wrapper.unmount()
  })
})

describe('分类页: 年份档来自 /filter-meta 的切片', () => {
  beforeEach(async () => {
    vi.clearAllMocks()
    vi.stubGlobal('IntersectionObserver', FakeIntersectionObserver)
    Element.prototype.scrollIntoView = vi.fn()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
  })

  /**
   * 后端给的是**库里真实存在的**年份前缀倒序全量. 里面混着 2027/2028/2029 那批
   * 未上映的(来源是「剧场版」关键词回源时拉到的已定档条目)以及更老的老片.
   *
   * 切片而不是在代码里写死 2026..2006: 明年不用改代码, 而且天然只列出库里真有数据的
   * 年份. 这里喂 49 和 29 开头的, 就是要它们被挡在外面.
   */
  it('只留不晚于今年的, 而且最多 20 个', async () => {
    const years = ['2049', '2029', ...Array.from({ length: 30 }, (_, i) => String(2026 - i))]
    getFilterMeta.mockResolvedValue(metaResponse(years))
    getFiltered.mockResolvedValue(filteredPage([card(1)]))
    await router.push('/tags')
    await router.isReady()

    const wrapper = mountTags()
    await flushPromises()

    const rendered = group(wrapper, '年份').findAll('.filter-option').map((b) => b.text())
    expect(rendered).toHaveLength(20)
    expect(rendered[0]).toBe('2026')
    expect(rendered).not.toContain('2049')
    expect(rendered).not.toContain('2029')
    // 状态档也不是写死的: 它的 label 同样来自接口
    expect(optionIn(wrapper, '状态', '放送中').exists()).toBe(true)

    wrapper.unmount()
  })
})

describe('分类页: 窄屏抽屉', () => {
  beforeEach(async () => {
    vi.clearAllMocks()
    vi.stubGlobal('IntersectionObserver', FakeIntersectionObserver)
    Element.prototype.scrollIntoView = vi.fn()
    getFilterMeta.mockResolvedValue(metaResponse())
    getFiltered.mockResolvedValue(filteredPage([card(1)]))

    await router.push('/tags')
    await router.isReady()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
  })

  // 「筛选」按钮在桌面上是 display:none, 但仍在 DOM 里(媒体查询管的是可见性,
  // jsdom 不做布局) —— 所以只能靠类名断言"抽屉开没开", 看不到它到底占不占位置
  it('点「筛选」开抽屉、点遮罩关抽屉', async () => {
    const wrapper = mountTags()
    await flushPromises()

    expect(wrapper.find('.filter-panel').classes()).not.toContain('open')

    await wrapper.find('.filter-toggle').trigger('click')
    expect(wrapper.find('.filter-panel').classes()).toContain('open')
    expect(wrapper.find('.filter-scrim').exists()).toBe(true)

    await wrapper.find('.filter-scrim').trigger('click')
    expect(wrapper.find('.filter-panel').classes()).not.toContain('open')

    wrapper.unmount()
  })

  // 不自动关的话, 用户选完一个条件还要手动再关一次才看得见结果 —— 而结果就是
  // 他点这一下的目的
  it('抽屉里选中一个条件后自动关上', async () => {
    const wrapper = mountTags()
    await flushPromises()

    await wrapper.find('.filter-toggle').trigger('click')
    await optionIn(wrapper, '题材', '机甲').trigger('click')
    await flushPromises()

    expect(wrapper.find('.filter-panel').classes()).not.toContain('open')
    expect(wrapper.text()).toContain('番剧1')

    wrapper.unmount()
  })

  it('开关上带一个已选条件数', async () => {
    const wrapper = mountTags()
    await flushPromises()

    expect(wrapper.find('.filter-toggle-count').exists()).toBe(false)

    await optionIn(wrapper, '题材', '机甲').trigger('click')
    await flushPromises()
    await optionIn(wrapper, '年份', '2025').trigger('click')
    await flushPromises()

    expect(wrapper.find('.filter-toggle-count').text()).toBe('2')

    wrapper.unmount()
  })
})
