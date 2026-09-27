/**
 * 工具名 -> 给人看的标签.
 *
 * 模型看到的是 get_ranking 这样的标识符, 用户看到的是「排行榜」——
 * 把调用过程摊开给用户看的前提是这一步先翻译成人话, 否则展示出来的
 * 只是一串英文函数名, 反而显得像报错日志.
 */
const TOOL_META = {
  // 公开工具
  search_anime: { label: '搜索番剧', icon: '🔍' },
  get_anime_detail: { label: '番剧详情', icon: '📖' },
  get_episodes: { label: '剧集列表', icon: '🎞️' },
  get_ranking: { label: '排行榜', icon: '🏆' },
  get_latest: { label: '最新上架', icon: '🆕' },
  get_calendar: { label: '每日放送', icon: '📅' },
  get_by_tag: { label: '按标签找番', icon: '🏷️' },
  filter_anime: { label: '条件筛选', icon: '🎛️' },
  list_tags: { label: '标签列表', icon: '🔖' },
  read_reviews: { label: '读取评论', icon: '💬' },
  get_rating_stats: { label: '评分分布', icon: '⭐' },
  get_anime_popularity: { label: '热度数据', icon: '📈' },

  // 需登录
  list_my_tracking: { label: '我的追番', icon: '📚' },
  add_or_update_tracking: { label: '更新追番', icon: '➕' },
  remove_tracking: { label: '取消追番', icon: '➖' },
  toggle_episode_watched: { label: '勾选已看', icon: '✅' },
  get_my_stats: { label: '我的统计', icon: '📊' },
  write_review: { label: '发表评论', icon: '✍️' },

  // 管理员
  platform_dashboard: { label: '平台概览', icon: '📊' },
  list_users: { label: '用户列表', icon: '👥' },
  list_all_reviews: { label: '全部评论', icon: '💬' },
  analyze_anime_heat: { label: '热度分析', icon: '🔥' },
  weekly_ops_report: { label: '运营周报', icon: '📋' },
}

/** 取工具的中文标签; 没登记的工具退回工具名本身, 不至于显示成空白 */
export function toolMeta(name) {
  return TOOL_META[name] || { label: name || '未知工具', icon: '🔧' }
}

export default TOOL_META
