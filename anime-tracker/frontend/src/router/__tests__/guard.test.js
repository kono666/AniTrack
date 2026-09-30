import { describe, it, expect, beforeEach } from 'vitest'
import router from '../../router'

/**
 * 路由守卫.
 *
 * 这一组用例盯的是「storage 里的坏数据会不会把整个站弄白屏」. 守卫是每一次跳转的
 * 必经之路, 它抛异常的表现不是某个页面坏掉, 而是**怎么点都不动**, 刷新也没用 ——
 * 而 storage 恰恰是用户和任何脚本都能改的地方.
 *
 * 所以除了「三种输入都返回 null」那种模块级用例(见 utils/__tests__/userStorage.test.js),
 * 这里还要钉住**守卫真的用了那个安全入口**: 光有安全函数、调用点却自己 JSON.parse,
 * 模块级用例再全也发现不了.
 */
describe('路由守卫', () => {
  beforeEach(async () => {
    localStorage.clear()
    // 每次都回到同一个中立起点. 不这么做的话, 「push 到当前已经所在的路径」会被
    // vue-router 当成重复导航直接跳过 —— 守卫根本不跑, 用例会假绿或假红
    await router.push('/')
    expect(router.currentRoute.value.path).toBe('/')
  })

  it('storage 里是坏 JSON 时, 跳转照常完成, 并把坏数据清掉', async () => {
    // 改前: 守卫里那句 JSON.parse 会在这里抛, 于是每一次跳转都失败 —— 全站白屏
    localStorage.setItem('anime_user', '{"token":"abc"')

    await router.push('/login')

    expect(router.currentRoute.value.name).toBe('Login')
    // 清掉了才算真的走了 loadStoredUser(): 只包一层 try/catch 的话它会留在原地,
    // 下一次读取继续踩同一个坑
    expect(localStorage.getItem('anime_user')).toBeNull()
  })

  it('未登录访问需要登录的页面 -> 跳登录页并带上原目标', async () => {
    await router.push('/profile')

    expect(router.currentRoute.value.path).toBe('/login')
    expect(router.currentRoute.value.query.redirect).toBe('/profile')
  })

  it('已登录(有 token)访问需要登录的页面 -> 放行', async () => {
    // 这条是上一条的对照. 判断「算不算已登录」的条件这次被改写过
    // (原来是 user && user.token, 现在是 user !== null, 因为安全入口保证了
    // 取出来的对象必有非空 token), 得钉住语义没变
    localStorage.setItem('anime_user', JSON.stringify({ username: 'u', token: 'jwt', role: 'USER' }))

    await router.push('/profile')

    expect(router.currentRoute.value.name).toBe('Profile')
  })

  it('有用户对象但没有 token -> 当作未登录', async () => {
    localStorage.setItem('anime_user', JSON.stringify({ username: 'u', role: 'USER' }))

    await router.push('/profile')

    expect(router.currentRoute.value.path).toBe('/login')
  })

  it('普通用户访问管理端 -> 回首页', async () => {
    localStorage.setItem('anime_user', JSON.stringify({ username: 'u', token: 'jwt', role: 'USER' }))

    await router.push('/admin/users')

    expect(router.currentRoute.value.path).toBe('/')
  })

  it('管理员访问管理端 -> 放行', async () => {
    localStorage.setItem('anime_user', JSON.stringify({ username: 'a', token: 'jwt', role: 'ADMIN' }))

    await router.push('/admin/users')

    expect(router.currentRoute.value.name).toBe('AdminUsers')
  })
})

/**
 * 后台路由的形状: 一条父路由 + 三个子路由.
 *
 * 上面两条守卫用例只覆盖了 /admin/users 一个地址, 而这次重构真正改的是
 * **形状** —— 三条平级路由收成一条父路由带三个子路由. 形状错了的典型症状不是
 * 这里红, 而是运行时某一处才炸: router-link 的 to="{ name: 'AdminUsers' }"
 * 找不到名字、或者 /admin 直接变成父路由那条没有 outlet 的空壳.
 *
 * 所以这里把三件"想当然会成立"的事各钉一条:
 *   · 三个地址分别落在三个子路由名字上;
 *   · 页面名在子路由上 (meta 是合并的, 后面的记录赢);
 *   · 父路由的 requiresAdmin 被子路由继承 —— 上面「普通用户访问管理端 -> 回首页」
 *     依赖的正是它, 而它成立的前提是 meta 合并的顺序.
 */
describe('后台路由的形状', () => {
  it('三个地址各自落在三个子路由名字上', () => {
    expect(router.resolve('/admin').name).toBe('AdminDashboard')
    expect(router.resolve('/admin/users').name).toBe('AdminUsers')
    expect(router.resolve('/admin/reviews').name).toBe('AdminReviews')
  })

  it('页面名挂在子路由上, 权限仍来自父路由', () => {
    const resolved = router.resolve('/admin/users')

    // 子路由的 title 覆盖父的路由(父路由刻意不写 title)
    expect(resolved.meta.title).toBe('用户管理')
    // 父路由的 requiresAdmin 必须被子继承 —— 少了它, 后台就是敞开的大门
    expect(resolved.meta.requiresAdmin).toBe(true)
    expect(resolved.meta.requiresAuth).toBe(true)
    // 父 + 子两层. 变成 1 说明子路由被拍平了, 3 以上说明嵌套错了层级
    expect(resolved.matched).toHaveLength(2)
  })

  it('三个子页共用同一个外壳组件, 各自挂各自的页面', () => {
    // 「外壳归谁管」这一条在路由表上就看得出来: 两条后台路径的第 0 层必须是
    // **同一个**组件(懒加载的 import 函数是模块级的常量, 同一个就是同一个),
    // 第 1 层必须是两个不同的. 反过来(第 0 层不同)就是改回了三条平级路由 ——
    // 那正是这次重构要修的东西, 而它在运行时只表现为"每次导航重播一次动画"
    const users = router.resolve('/admin/users')
    const reviews = router.resolve('/admin/reviews')

    expect(users.matched[0].components.default).toBe(reviews.matched[0].components.default)
    expect(users.matched[1].components.default).not.toBe(reviews.matched[1].components.default)
  })
})
