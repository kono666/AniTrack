/**
 * 首页数据的进程内缓存.
 *
 * 为什么是一份独立模块, 而不是 Home.vue 里的一个对象: 这份缓存必须活在组件
 * **实例之外**.
 *
 * 改前它写在 Home.vue 的 <script setup> 里, 而 setup 里的代码每挂载一次就
 * 跑一次 —— 缓存是跟着组件实例走的. 用户从首页点进详情页时 Home 被卸载,
 * 这份缓存跟着一起没了, 回到首页又是一次全新的请求. 首页是全站最常走的
 * 中转站(几乎每次返回都要经过它), 所以"5 分钟缓存"这件事实际上从来没有
 * 生效过, 只有注释里写着有.
 *
 * 放在模块里, 整个页面生命周期内只有一份, TTL 才谈得上生效.
 *
 * 代价: 它不会随着登录/登出而失效. 这是可以接受的 —— 缓存的这几个接口
 * (排行、日历)本来就与登录态无关, 不随用户变化.
 */

export const HOME_CACHE_TTL = 5 * 60 * 1000

/** 只有核心数据(排行/更新)全部拿到时才写, 见 Home.vue 里的 coreLoaded. */
export const homeCache = { data: null, time: 0 }

/** 给测试用: 断言缓存命中之前需要把它清回初始态. */
export function resetHomeCache() {
  homeCache.data = null
  homeCache.time = 0
}
