/**
 * 「登录后回哪去」—— 全站只从这里决定.
 *
 * 为什么值得单独一个模块: 跳登录页的入口不止路由守卫一个. 导航栏的「登录」、
 * 「+ 追番」、评论区的「登录后参与讨论」、Profile 里的操作, 都是各自 push('/login'),
 * 而它们都没有带 query —— 于是登录成功后 route.query.redirect 是空的, 只能回首页.
 * 用户点的是「追番」, 登录完却站在首页, 刚才那一页得自己找回来.
 *
 * 与其在每个入口都记得带上 redirect(总会有新入口忘记, 而且忘了不会有任何提示),
 * 不如在这里记一笔: 每次成功导航到站内页面之后把「刚才那一页」存下来, 登录页在
 * URL 上没有 redirect 时拿它兜底.
 *
 * 两个边界:
 *  * 登录页/注册页本身不记 —— 否则连点两次「登录」, 兜底就成了登录页自己;
 *  * 只认站内路径(必须以 / 开头), 别的一律不进这个变量.
 */

let lastPath = null

const AUTH_PATHS = ['/login', '/register']

function isAuthPage(fullPath) {
  return AUTH_PATHS.some(p => fullPath === p || fullPath.startsWith(`${p}?`) || fullPath.startsWith(`${p}/`))
}

/** 记下「刚才那一页」. 由 router.afterEach 调用, 别在别处调. */
export function rememberPath(fullPath) {
  if (typeof fullPath !== 'string' || !fullPath.startsWith('/')) return
  if (isAuthPage(fullPath)) return
  lastPath = fullPath
}

/** 站内路径的判定: 必须以 / 开头, 但不是 // 或 /\ 开头(见下). */
function isSafeInternalPath(value) {
  return typeof value === 'string' && value.startsWith('/') && !value.startsWith('//') && !value.startsWith('/\\')
}

/**
 * 登录/注册成功后的落点.
 *
 * 优先级: URL 上的 ?redirect= > 刚才那一页 > 首页.
 *
 * 第一条为什么必须压在最前面, 以及为什么**不能只判断"以 / 开头"**:
 * 这个参数来自 URL, 谁都能构造一条 /login?redirect=//evil.com 发出去. 以 //
 * 开头的是协议相对地址, 浏览器认它, pushState 之后当前标签页就直接跳到 evil.com
 * 了 —— 而这一步发生在**登录成功之后**, 正是用户刚输完密码、最不会怀疑接下来
 * 那一跳的时候. 反斜杠那一版(/\evil.com)在部分浏览器里同样会被当成 // 处理,
 * 一起挡掉.
 *
 * 不合规的时候往下一步走, 而不是直接回首页: 有人构造一条
 * /login?redirect=https://evil.com 发给别人, 那个人登录后应该回到他刚才在的地方,
 * 没道理因为别人发的链接受罚.
 */
export function resolvePostAuthPath(raw) {
  if (isSafeInternalPath(raw)) return raw
  if (isSafeInternalPath(lastPath)) return lastPath
  return '/'
}

/** 仅供测试: 清掉记住的路径, 免得用例之间互相影响. */
export function resetRememberedPath() {
  lastPath = null
}
