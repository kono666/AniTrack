import { defineStore } from 'pinia'
import { ref } from 'vue'
import { getUnreadCount } from '../api'

/**
 * 未读通知数 —— 导航栏那个红点的唯一数据源.
 *
 * 【为什么是一个 store 而不是导航栏自己拉】
 * 有**两个**地方要知道它: 导航栏(显示红点)和个人页(进去就把未读清零)。分成两份
 * 状态的话, 用户在个人页读完通知, 红点得等导航栏自己发现这件事 —— 而导航栏在
 * 应用外壳里, 页面切换不会让它重新挂载。于是"点进去红点还在"就成了必然, 而不是
 * 偶发。放在 store 里, 个人页清完直接改同一个数字, 两边同时变。
 *
 * 【失败一律当 0, 不抛也不留旧值】
 * 这个数字驱动的是一个**提示**, 不是内容: 拉不到的时候最坏的结果应该是"没有红点",
 * 而不是导航栏崩掉、或者一个永远点不掉的幽灵红点留在那里。所以 refresh() 自己把
 * 异常咽掉、把值设回 0 —— 调用方不需要(也不该)写 try/catch。
 *
 * 顺带一句: 未登录时后端会回 401, 走的就是上面那条路径 —— 于是这里不需要先判
 * "登录了没有"。那种判断是多余的, 而且它一旦与 store 里的登录态判断不同步
 * (比如登出时忘了清), 症状就是游客看到一个红点。
 */
export const useNotificationStore = defineStore('notification', () => {
  const unreadCount = ref(0)

  /** 重新问一次服务端。失败当 0(见文件头) */
  async function refresh() {
    try {
      const res = await getUnreadCount()
      // Number(...) || 0 兜住"字段没了""字段是字符串"这两种脏数据: NaN 落到红点上
      // 会渲染成空白, 而"看起来没有未读"比"看起来坏了"更接近真相
      unreadCount.value = Number(res.data?.data?.count) || 0
    } catch (e) {
      unreadCount.value = 0
    }
  }

  /**
   * 就地清零, 不发请求 —— 给"刚刚把未读标成已读"那条路用.
   *
   * 它比 refresh() 更准: 标已读之后我们**知道**结果是 0, 再问一次服务端只是把同一件
   * 事问第二遍, 而且中间那一次往返里红点还亮着。
   */
  function clear() {
    unreadCount.value = 0
  }

  return { unreadCount, refresh, clear }
})
