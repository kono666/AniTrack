import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { loadStoredUser, saveStoredUser, clearStoredUser } from '../utils/userStorage'

export const useUserStore = defineStore('user', () => {
  // 走 loadStoredUser() 而不是自己 JSON.parse: store 是在组件树建立时被创建的,
  // 这里抛异常等于整个应用起不来(而 storage 里放的是一段任何人都能改的数据).
  // 解析失败时它返回 null, 于是最坏的结果只是「看起来没登录」.
  const user = ref(loadStoredUser())
  // 双叹号不是多余的: `user.value !== null && user.value.token` 的结果是**那个
  // token 字符串**(&& 返回的是操作数, 不是布尔值), 也就是说这个名字叫 loggedIn
  // 的计算属性会算出 'jwt' 这种值. 所有调用点都恰好用在布尔上下文里(v-if / ! / ||),
  // 所以一直没出问题, 但 `loggedIn === true` 这种写法会当场踩空.
  const loggedIn = computed(() => !!(user.value !== null && user.value.token))

  function setUser(data) {
    user.value = data
    saveStoredUser(data)
  }

  /**
   * 只换头像地址, 别的一个字段都不动.
   *
   * <p>刻意**不**用 {@code setUser({ avatar })}: 那是整份替换, 会把 token、username、
   * role 一起抹掉 —— 而 token 没了的表现是"换个头像就被登出了"。所以这里合并。
   *
   * <p>它同时也让 {@code localStorage} 里的那份跟着更新(走 setUser), 于是刷新页面后
   * 头像仍然是新的 —— 少了这一步, 用户会看到"传完了、一刷新又变回去了"。
   */
  function setAvatar(url) {
    if (!user.value) return
    setUser({ ...user.value, avatar: url })
  }

  function logout() {
    user.value = null
    clearStoredUser()
  }

  const token = computed(() => user.value?.token || null)

  return { user, loggedIn, token, setUser, setAvatar, logout }
})
