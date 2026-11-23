/**
 * 首页首屏「编辑推荐」片单.
 *
 * 两组各 6 部：
 *   - 站主推荐：由站长人工挑选，表达这个站的品味；
 *   - 热度推荐：按站内评分人数（rating_count）取的 top 6，排除已入选站主推荐的那几部。
 *
 * 为什么是写死的数据而不是接口：
 *   1. 宽幅 key art（banner）不在 Bangumi 的数据里，是 12 张单独下载到 public/hero/
 *      的图片（2560 宽的 2.4:1 裁剪），跟片单一一对应，改片单必须同时换图；
 *   2. 首屏要保证一定不空——接口挂了、上游连不上时，这一屏仍然能画出来。
 *      这与页面下方那些「加载失败就显示错误态」的列表是两种不同的取舍。
 *
 * id 是 Bangumi 的 subject id，点击后走 /anime/{id}，与站内详情页一致。
 * cover 走同源图片代理（见后端 CoverImages.PROXY_PATH），与站内其它封面同一条路。
 * 图片素材来自 TMDB（https://www.themoviedb.org/），仅用于非商业的课程/毕设展示。
 */

const PROXY = '/api/img?url='

const cover = (url) => PROXY + encodeURIComponent(url)

export const HERO_SHELF = [
  // ── 站主推荐 ──
  {
    id: 185943,
    group: '站主推荐',
    title: '末日时在做什么？有没有空？可以来拯救吗？',
    original: '終末なにしてますか？忙しいです？救ってもらっていいですか？',
    score: '7.6',
    year: '2017',
    tags: ['奇幻', '治愈', '轻小说改'],
    banner: '/hero/sukascaa.jpg',
    cover: cover('https://lain.bgm.tv/pic/cover/l/a0/5f/185943_VDh22.jpg'),
  },
  {
    id: 849,
    group: '站主推荐',
    title: '出包王女',
    original: 'To LOVEる -とらぶる-',
    score: '7.0',
    year: '2008',
    tags: ['喜剧', '恋爱', '后宫'],
    banner: '/hero/tolove.jpg',
    cover: cover('https://lain.bgm.tv/pic/cover/l/fe/8c/849_9xbj1.jpg'),
  },
  {
    id: 239816,
    group: '站主推荐',
    title: '关于我转生变成史莱姆这档事',
    original: '転生したらスライムだった件',
    score: '6.7',
    year: '2018',
    tags: ['异世界', '奇幻', '冒险'],
    banner: '/hero/slime.jpg',
    cover: cover('https://lain.bgm.tv/pic/cover/l/7b/d6/239816_BbNdb.jpg'),
  },
  {
    id: 54433,
    group: '站主推荐',
    title: '我的青春恋爱物语果然有问题',
    original: 'やはり俺の青春ラブコメはまちがっている。',
    score: '7.5',
    year: '2013',
    tags: ['校园', '恋爱', '青春'],
    banner: '/hero/oregairu.jpg',
    cover: cover('https://lain.bgm.tv/pic/cover/l/1e/f1/54433_JZ99l.jpg'),
  },
  {
    id: 245665,
    group: '站主推荐',
    title: '鬼灭之刃',
    original: '鬼滅の刃',
    score: '7.3',
    year: '2019',
    tags: ['战斗', '热血', '奇幻'],
    banner: '/hero/demon.jpg',
    cover: cover('https://lain.bgm.tv/pic/cover/l/9d/d1/245665_5an54.jpg'),
  },
  {
    id: 294993,
    group: '站主推荐',
    title: '咒术回战',
    original: '呪術廻戦',
    score: '7.1',
    year: '2020',
    tags: ['战斗', '校园', '奇幻'],
    banner: '/hero/jjk.jpg',
    cover: cover('https://lain.bgm.tv/pic/cover/l/60/fe/294993_JrrzK.jpg'),
  },

  // ── 热度推荐（按站内评分人数） ──
  {
    id: 328609,
    group: '热度推荐',
    title: '孤独摇滚！',
    original: 'ぼっち・ざ・ろっく！',
    score: '8.4',
    year: '2022',
    tags: ['音乐', '日常', '轻百合'],
    banner: '/hero/bocchi.jpg',
    cover: cover('https://lain.bgm.tv/pic/cover/l/e2/e7/328609_2EHLJ.jpg'),
  },
  {
    id: 10380,
    group: '热度推荐',
    title: '命运石之门',
    original: 'STEINS;GATE',
    score: '8.8',
    year: '2011',
    tags: ['科幻', '悬疑', '轮回'],
    banner: '/hero/steins.jpg',
    cover: cover('https://lain.bgm.tv/pic/cover/l/a9/79/10380_YwP4R.jpg'),
  },
  {
    id: 400602,
    group: '热度推荐',
    title: '葬送的芙莉莲',
    original: '葬送のフリーレン',
    score: '8.5',
    year: '2023',
    tags: ['奇幻', '公路片', '治愈'],
    banner: '/hero/frieren.jpg',
    cover: cover('https://lain.bgm.tv/pic/cover/l/13/c5/400602_ZI8Y9.jpg'),
  },
  {
    id: 9717,
    group: '热度推荐',
    title: '魔法少女小圆',
    original: '魔法少女まどか☆マギカ',
    score: '8.6',
    year: '2011',
    tags: ['奇幻', '剧情', '原创'],
    banner: '/hero/madoka.jpg',
    cover: cover('https://lain.bgm.tv/pic/cover/l/cb/57/9717_sAVag.jpg'),
  },
  {
    id: 265,
    group: '热度推荐',
    title: '新世纪福音战士',
    original: '新世紀エヴァンゲリオン',
    score: '8.7',
    year: '1995',
    tags: ['科幻', '机战', '心理'],
    banner: '/hero/eva.jpg',
    cover: cover('https://lain.bgm.tv/pic/cover/l/e5/69/265_Z5Uou.jpg'),
  },
  {
    id: 55770,
    group: '热度推荐',
    title: '进击的巨人',
    original: '進撃の巨人',
    score: '8.2',
    year: '2013',
    tags: ['热血', '战斗', '奇幻'],
    banner: '/hero/titan.jpg',
    cover: cover('https://lain.bgm.tv/pic/cover/l/78/c9/55770_HsJfh.jpg'),
  },
]

/** 每组 6 部 —— 序号按组内计（No.01 起） */
export const HERO_GROUP_SIZE = 6
