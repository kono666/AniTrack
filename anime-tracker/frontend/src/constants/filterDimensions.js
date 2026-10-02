/**
 * 分类浏览页(/tags)的六个维度里, 由前端决定的那些.
 *
 * 年份与状态**不在这里** —— 它们来自后端的 `/filter-meta`(库里真有哪些年份、状态
 * 有哪几种), 单值, 而且明年不会变成别的样子. 这一份是"库里那四万多个原始标签里,
 * 哪一些值得摆成按钮、怎么合并、按什么顺序".
 *
 * 季度({@link SEASON_QUARTERS})是个例外: 它**不来自 /filter-meta** —— 一年永远
 * 四个季度、不依赖库里有什么数据, 让后端为它多算一份投影换不到任何东西. 它是
 * 前端唯一一个"固定四个选项"的组, 选项本身就写在这里.
 *
 * ── 为什么这一份在前端, 不放后端 ────────────────────────────────
 *
 * 它是**呈现元数据**, 不是筛选能力的声明: 显示哪些按钮、什么顺序、一个按钮背后
 * 是哪几个标签名. 走线时前端已经把 slug 展开成标签名, 后端只认标签名、根本不需要
 * 知道"题材/载体"这回事(见 AnimeService.FilterQuery.fromCsv)。
 *
 * 代价是 AI 助手拿不到这套合并规则 —— 那是刻意的: 助手用原始标签名表达力更强
 * ("找某某声优的作品"正是它该干的, 而那样的问题进不了这份封闭词表).
 *
 * ── 三个字段各是什么 ────────────────────────────────────────────
 *
 * · value —— ASCII slug, **只出现在 URL 上**(`?genre=mecha,fantasy`). 用 slug
 *   而不是中文: URL 短、可读、不含百分号编码, 而且以后想换合并规则不必作废旧链接.
 * · label —— 按钮上的中文.
 * · tags  —— 这个按钮背后**真实存在于 tag 表里**的标签名, 一个或多个.
 *
 * ── 一个按钮为什么要展开成好几个标签名 ──────────────────────────
 *
 * 库里的标签是一份没有分类的民间词表, 同一个概念有好几种写法(「搞笑」5117 条与
 * 「喜剧」1518 条是两拨人在两个时期打的). 主流平台的维度是封闭词表, 所以这里把
 * 同义的并成一个按钮 —— 不并的话, 用户点「搞笑」会漏掉一万多条挂着「喜剧」的番,
 * 而他没有任何理由知道自己漏了.
 *
 * labels 里的名字**每一个都必须在库里存在**. 不存在的名字不会报错, 只会静默少筛
 * 一批 —— 单测抓不到这件事, 只能靠对着库核(见提交说明里的核对方式).
 */

/** 四个多选组, 顺序就是侧栏里从上到下的顺序 */
export const FILTER_GROUPS = [
  {
    key: 'genre',
    label: '题材',
    multi: true,
    /**
     * 题材默认只摆前 12 个, 其余收在「展开全部」后面.
     *
     * MAL 的 "Show All" 是同一个做法. 不折叠的话侧栏第一屏全是题材 —— 另外五组
     * (载体/来源/地区/年份/状态)会被推到要滚动才看得见的地方, 而它们恰恰是更常用
     * 的收窄手段.
     */
    collapsedCount: 12,
    options: [
      { value: 'fantasy', label: '奇幻', tags: ['奇幻', '魔幻', '玄幻'] },
      { value: 'comedy', label: '搞笑', tags: ['搞笑', '喜剧', '欢乐', '恶搞'] },
      { value: 'action', label: '战斗', tags: ['战斗', '动作', '燃'] },
      { value: 'scifi', label: '科幻', tags: ['科幻', 'SF'] },
      { value: 'slice', label: '日常', tags: ['日常'] },
      { value: 'romance', label: '恋爱', tags: ['恋爱', '爱情'] },
      { value: 'healing', label: '治愈', tags: ['治愈'] },
      { value: 'school', label: '校园', tags: ['校园'] },
      { value: 'hotblooded', label: '热血', tags: ['热血'] },
      { value: 'adventure', label: '冒险', tags: ['冒险'] },
      { value: 'mecha', label: '机甲', tags: ['机战', '萝卜', '机甲', '机器人'] },
      { value: 'yuri', label: '百合', tags: ['百合', '轻百合', '轻百'] },
      { value: 'isekai', label: '异世界', tags: ['异世界', '穿越', '转生'] },
      { value: 'harem', label: '后宫', tags: ['后宫'] },
      { value: 'mystery', label: '悬疑', tags: ['悬疑', '推理', '智斗'] },
      { value: 'drama', label: '剧情', tags: ['剧情'] },
      { value: 'music', label: '音乐', tags: ['音乐'] },
      { value: 'ecchi', label: '卖肉', tags: ['卖肉', '福利'] },
      { value: 'youth', label: '青春', tags: ['青春'] },
      { value: 'sports', label: '运动', tags: ['运动', '体育'] },
      { value: 'bl', label: '耽美', tags: ['耽美', '基', '腐'] },
      { value: 'war', label: '战争', tags: ['战争'] },
      { value: 'idol', label: '偶像', tags: ['偶像'] },
      { value: 'history', label: '历史', tags: ['历史'] },
      { value: 'tearjerker', label: '催泪', tags: ['催泪', '致郁'] },
      { value: 'inspirational', label: '励志', tags: ['励志'] },
      { value: 'superpower', label: '超能力', tags: ['超能力', '超现实'] },
      { value: 'wuxia', label: '武侠', tags: ['武侠'] },
      { value: 'moe', label: '萌系', tags: ['萌系', '正太'] },
      { value: 'otome', label: '乙女', tags: ['乙女', '少女向'] },
      { value: 'workplace', label: '职场', tags: ['职场'] },
      { value: 'horror', label: '恐怖', tags: ['恐怖', '血腥'] },
    ],
  },
  {
    key: 'medium',
    label: '载体',
    multi: true,
    options: [
      { value: 'tv', label: 'TV', tags: ['TV', 'TVA', 'TVSP'] },
      { value: 'movie', label: '剧场版', tags: ['剧场版', '动画电影', '电影', '映画', '剧场', '独立剧场版'] },
      { value: 'web', label: 'WEB', tags: ['WEB', 'ONA'] },
      { value: 'ova', label: 'OVA', tags: ['OVA', '独立ova'] },
      { value: 'short', label: '短片', tags: ['短片', '短篇', '短片集', 'Short', 'Short_film'] },
      { value: 'instant', label: '泡面番', tags: ['泡面番', '泡面'] },
    ],
  },
  {
    key: 'source',
    label: '来源',
    multi: true,
    options: [
      { value: 'original', label: '原创', tags: ['原创'] },
      { value: 'manga', label: '漫画改', tags: ['漫画改', '漫改'] },
      { value: 'novel', label: '小说改', tags: ['小说改', '轻小说改', '轻改'] },
      { value: 'game', label: '游戏改', tags: ['游戏改'] },
      { value: 'other', label: '其他改编', tags: ['同人', '影视改', '绘本', '动画改', '真人'] },
    ],
  },
  {
    key: 'region',
    label: '地区',
    multi: true,
    options: [
      { value: 'jp', label: '日本', tags: ['日本', '日本动画', '日漫'] },
      { value: 'cn', label: '中国', tags: ['中国', '国产', '国漫', '中国动画', '国产动画', '大陆'] },
      { value: 'west', label: '欧美', tags: ['欧美', '美国', '美国动画', '欧洲', '法国', '英国', '德国', '意大利', '西班牙', '加拿大', '捷克', '迪士尼'] },
      { value: 'kr', label: '韩国', tags: ['韩国'] },
      { value: 'other', label: '其他地区', tags: ['苏联', '俄罗斯', '香港', '台湾', '马来西亚'] },
    ],
  },
]

/** 多选组的 key, 顺序与上面一致 —— 走线、写 URL、清空都按这个顺序来 */
export const MULTI_KEYS = FILTER_GROUPS.map((g) => g.key)

/**
 * 年份 / 季度 / 状态是**单选**: 它们在 URL 上是普通字符串, 不是逗号串.
 *
 * 分类页的 `EMPTY_SELECTION` / `readQuery` / `syncQuery` / `selectionKey` /
 * `activeCount` 五处都按这个数组循环 —— 加一个单值维度只改这一行, 五处一起跟上.
 * 之前它是**死代码**(零 import), 于是那五处各自硬编码 `year` 与 `status`,
 * 加季度时要改五个地方; 而漏改任何一处的表现都不是报错: `selectionKey` 漏了它,
 * 条件就"写了 URL 却读不回来", 页面不动、控制台干净.
 */
export const SINGLE_KEYS = ['year', 'season', 'status']

/**
 * 季度的四个选项 —— 一年固定四个, 与库里有没有数据无关.
 *
 * `q` 是**季度序号**(1..4), 也就是拼进 URL 的那一位(`2024-Q4`): `season` 这一列
 * 存的是真实月份(`2024-10`), 由后端把 `2024-Q4` 展开成 10/11/12 三个月 —— 展开
 * 放在服务端而不是这里, 是为了让 AI 工具与直接调 API 的调用方拿到同一套语义
 * (它们不经过这个文件).
 *
 * ⚠️ `q` **不是月份**. 写成起始月 1/4/7/10 会拼出 `2024-Q10` 这种值, 后端按
 * "认不出的值"处理 → 静默筛空, 而界面上四个按钮一个都不亮、也不报错. `q` 就是
 * 后端那一端 `^(\d{4})-Q[1-4]$` 里的那个数字.
 *
 * label 写「10月」而不是「秋季」: 中文"秋季"的月份划分各平台不一(有的从九月算起),
 * 而用户点下去得到的是哪几个月必须一眼看得出来.
 */
export const SEASON_QUARTERS = [
  { q: 1, label: '1月' },   // Q1 = 1/2/3 月
  { q: 2, label: '4月' },   // Q2 = 4/5/6 月
  { q: 3, label: '7月' },   // Q3 = 7/8/9 月
  { q: 4, label: '10月' },  // Q4 = 10/11/12 月
]

/**
 * 季度值 `yyyy-Qn` → 它属于哪一年; 不是这个形状就返回空串.
 *
 * 深链归一化与"换年份时清掉季度"两处共用它, 免得两处各写一遍正则 —— 而两处不一致
 * 的表现是"URL 上是 2024-Q4、按钮亮着 2023": 一个既筛不对、又看不出哪里不对的状态.
 */
export function seasonYear(value) {
  const m = /^(\d{4})-Q[1-4]$/.exec(value || '')
  return m ? m[1] : ''
}

const OPTIONS_BY_KEY = Object.fromEntries(FILTER_GROUPS.map((g) => [g.key, g.options]))

/**
 * slug 列表 → 一个组选中的标签名(保序去重).
 *
 * 认不出来的 slug 一律丢掉: 它们只可能来自被手改过的 URL, 而"发一个查不到东西的
 * 标签名"会让整组静默变成空结果(后端对"给了名字却一个都没解析出来"的处理就是
 * 返回空, 见 AnimeService.getFilteredPage)。丢掉之后那一组等于没选, 是更合理的
 * 解释 —— 手输错一个 slug 不该让整页空掉.
 */
export function tagNamesOf(key, values) {
  const options = OPTIONS_BY_KEY[key] || []
  const names = []
  for (const slug of values) {
    const option = options.find((o) => o.value === slug)
    if (!option) continue
    for (const name of option.tags) {
      if (!names.includes(name)) names.push(name)
    }
  }
  return names
}

/** slug 列表 → 中文 label 列表(空态文案要用"用户看得懂的那个词", 不是 slug) */
export function labelsOf(key, values) {
  const options = OPTIONS_BY_KEY[key] || []
  return values.map((slug) => options.find((o) => o.value === slug)?.label).filter(Boolean)
}

/** 这个 slug 是不是这一组里认得的选项 —— 读 URL 时用来丢掉不认识的 */
export function isKnownValue(key, value) {
  return (OPTIONS_BY_KEY[key] || []).some((o) => o.value === value)
}
