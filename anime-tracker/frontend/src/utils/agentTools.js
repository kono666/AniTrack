import {
  PhMagnifyingGlass, PhBookOpen, PhFilmStrip, PhTrophy, PhClock, PhCalendarBlank,
  PhTag, PhFaders, PhBookmarks, PhChatCircle, PhStar, PhChartLineUp, PhBooks,
  PhPlusCircle, PhMinusCircle, PhCheckCircle, PhChartBar, PhPencilSimple,
  PhUsers, PhFire, PhClipboardText, PhWrench,
} from '@phosphor-icons/vue'

/**
 * 工具名 -> 给人看的标签 + 图标.
 *
 * 模型看到的是 get_ranking 这样的标识符, 用户看到的是「排行榜」——
 * 把调用过程摊开给用户看的前提是这一步先翻译成人话, 否则展示出来的
 * 只是一串英文函数名, 反而显得像报错日志.
 *
 * 这里**是全站唯一一处在 c71 里把 emoji 换成图标而不是删掉的地方**: 别处的
 * emoji 都是标签前面的装饰(「今日放送」的文字本身就说明了一切), 而这里
 * 24 个工具排在一列时间线里, 图标是区分它们的**唯一**视觉线索 —— 删了就真的
 * 少了一层信息. 换掉的原因是老问题: emoji 的字形跟着系统字体走.
 *
 * 字段名从 `icon` 改成 `Icon`(首字母大写)是刻意的: 它的值现在是组件, 不是
 * 字符串, 用法是 `<component :is="…" />`. 小写的名字会让人以为还能直接打印.
 */
const TOOL_META = {
  // 公开工具
  search_anime: { label: '搜索番剧', Icon: PhMagnifyingGlass },
  get_anime_detail: { label: '番剧详情', Icon: PhBookOpen },
  get_episodes: { label: '剧集列表', Icon: PhFilmStrip },
  get_ranking: { label: '排行榜', Icon: PhTrophy },
  get_latest: { label: '最新上架', Icon: PhClock },
  get_calendar: { label: '每日放送', Icon: PhCalendarBlank },
  get_by_tag: { label: '按标签找番', Icon: PhTag },
  filter_anime: { label: '条件筛选', Icon: PhFaders },
  list_tags: { label: '标签列表', Icon: PhBookmarks },
  read_reviews: { label: '读取评论', Icon: PhChatCircle },
  get_rating_stats: { label: '评分分布', Icon: PhStar },
  get_anime_popularity: { label: '热度数据', Icon: PhChartLineUp },

  // 需登录
  list_my_tracking: { label: '我的追番', Icon: PhBooks },
  add_or_update_tracking: { label: '更新追番', Icon: PhPlusCircle },
  remove_tracking: { label: '取消追番', Icon: PhMinusCircle },
  toggle_episode_watched: { label: '勾选已看', Icon: PhCheckCircle },
  get_my_stats: { label: '我的统计', Icon: PhChartBar },
  write_review: { label: '发表评论', Icon: PhPencilSimple },

  // 管理员
  platform_dashboard: { label: '平台概览', Icon: PhChartBar },
  list_users: { label: '用户列表', Icon: PhUsers },
  list_all_reviews: { label: '全部评论', Icon: PhChatCircle },
  analyze_anime_heat: { label: '热度分析', Icon: PhFire },
  weekly_ops_report: { label: '运营周报', Icon: PhClipboardText },
}

/** 取工具的中文标签与图标; 没登记的工具退回工具名本身, 不至于显示成空白 */
export function toolMeta(name) {
  return TOOL_META[name] || { label: name || '未知工具', Icon: PhWrench }
}

export default TOOL_META
