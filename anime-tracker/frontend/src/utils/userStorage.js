/**
 * localStorage 里的用户态 —— 全站只从这里读写.
 *
 * 为什么值得单独一个模块: 在这个文件出现之前, `JSON.parse(localStorage.getItem('anime_user'))`
 * 这个写法散在四个地方(路由守卫、user store、axios 请求拦截器、SSE 取 token), 每一处
 * 都假设存进去的一定是一段合法 JSON 对象. 而 localStorage 是**用户和任何脚本都能改的**:
 * 一段脏数据(json 语法错、被截断、值是 `"null"`、结构不对)会让这几处就地抛异常. 后果
 * 按调用点各不相同, 但共同点是**都发生在应用最核心的路径上**:
 *
 *   * 路由守卫里抛 -> 每一次跳转都失败, 表现是整个站白屏, 而且刷新也没用
 *     —— 因为守卫在启动时就跑, 用户没有任何自助恢复的手段(要手动清 storage);
 *   * user store 初始化时抛 -> 组件树根本建立不起来;
 *   * axios 拦截器里抛 -> 所有请求都发不出去.
 *
 * 所以这里的策略是「宁可不认, 也不抛」: 解析失败或结构不合法, 就当没登录, 并把
 * 那条坏数据清掉 —— 留着它只会让下一次读取继续踩同一个坑.
 *
 * 这也顺带统一了「什么算已登录」的判断: 必须有非空的 token 字符串. 在此之前各处
 * 写的是 `user && user.token`, 也就是一个没有 token 的对象算未登录、却会一直留在
 * storage 里每次都被解析一遍. 现在这种对象会被清理掉.
 */

export const USER_STORAGE_KEY = 'anime_user'

/**
 * 读取已登录用户; 拿不到合法数据就返回 null 并把脏数据清掉.
 *
 * @returns {{token: string, [key: string]: any}|null}
 */
export function loadStoredUser() {
  let raw
  try {
    raw = localStorage.getItem(USER_STORAGE_KEY)
  } catch {
    // 极少数情况下访问 localStorage 本身就会抛(比如浏览器禁用了站点数据).
    // 这种环境里当作未登录处理, 总好过让调用方炸掉.
    return null
  }

  if (!raw) return null

  let parsed
  try {
    parsed = JSON.parse(raw)
  } catch {
    clearStoredUser()
    return null
  }

  // 只认「普通对象 + 非空 token 字符串」. 数组和字符串也能通过 JSON.parse,
  // 但它们身上不可能有 token, 属于脏数据的范畴, 一并清掉.
  const isPlainObject = typeof parsed === 'object' && parsed !== null && !Array.isArray(parsed)
  if (!isPlainObject || typeof parsed.token !== 'string' || !parsed.token) {
    clearStoredUser()
    return null
  }

  return parsed
}

/** 写入用户态(登录/注册成功后). */
export function saveStoredUser(user) {
  try {
    localStorage.setItem(USER_STORAGE_KEY, JSON.stringify(user))
  } catch {
    // 写不进去(配额满/隐私模式)不该让登录流程失败: 内存里的登录态仍然可用,
    // 只是刷新后需要重新登录
  }
}

/** 清除用户态(退出登录 / 收到 401). */
export function clearStoredUser() {
  try {
    localStorage.removeItem(USER_STORAGE_KEY)
  } catch {
    // 同上: 清不掉也不是需要用户处理的错误
  }
}
