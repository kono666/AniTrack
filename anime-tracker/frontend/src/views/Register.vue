<template>
  <div class="auth-page">
    <div class="auth-card">
      <h2>✨ 注册 AniTrack</h2>
      <form class="auth-form" @submit.prevent="handleRegister">
        <!-- new-password 而不是 password: 这是**新建**密码的场合, 说成
             password 会让浏览器把已存的旧密码填进注册表单 -->
        <label>用户名</label>
        <input v-model="form.username" type="text" placeholder="3-50个字符，中文/字母/数字/_-" autocomplete="username" required minlength="3" maxlength="50" />
        <label>邮箱</label>
        <input v-model="form.email" type="email" placeholder="example@mail.com" autocomplete="email" required maxlength="100" />
        <label>密码</label>
        <input v-model="form.password" type="password" placeholder="至少8位，需含字母和数字" autocomplete="new-password" required minlength="8" maxlength="100" />
        <label>确认密码</label>
        <input v-model="form.confirmPassword" type="password" placeholder="再次输入密码" autocomplete="new-password" required />
        <div v-if="error" class="auth-error">{{ error }}</div>
        <button class="auth-submit" type="submit" :disabled="loading">
          {{ loading ? '注册中...' : '注册' }}
        </button>
      </form>
      <div class="auth-link">
        已有账号？<router-link to="/login">去登录</router-link>
      </div>
    </div>
  </div>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useUserStore } from '../stores/user'
import { register } from '../api'

const router = useRouter()
const userStore = useUserStore()
const form = reactive({ username: '', email: '', password: '', confirmPassword: '' })
const error = ref('')
const loading = ref(false)

// 与后端 PasswordPolicy 保持一致. 后端那份是权威, 这份只是为了在提交前
// 就把问题拦下来, 省掉一次往返. 两边都写是没办法的事 —— 一个是 Java, 一个是 JS.
const PASSWORD_LETTER_AND_DIGIT = /^(?=.*[A-Za-z])(?=.*\d).*$/

/**
 * 与后端 UsernamePolicy 的 REGEX 保持一致: 只允许字母/数字/下划线/连字符.
 *
 * \p{L} 是"任意语言的字母", 不只是 A-Za-z —— 这个项目的用户会取「绫波丽」
 * 或者「ゆき」这样的名字, 用 [A-Za-z0-9_] 会把他们挡在门外. 需要 u 标志
 * 才能用这种属性转义.
 *
 * 为什么非要有这条规则: 用户名会被渲染在页面上、也会进 Agent 的上下文.
 * 不允许空格和不可见字符, 才能保证「admin」和「admin 」不会是两个账号 ——
 * 后者是冒充别人最省事的一招, 而肉眼分辨不出来.
 */
const USERNAME_ALLOWED = /^[\p{L}\p{N}_-]+$/u

function validate() {
  // 校验的是**将要发出去的那个值**(下面 handleRegister 发的是 trim 过的),
  // 所以这里也 trim: 否则用户在结尾多打一个空格, 会被前端拦下来,
  // 而后端收到的本来就是一个合法的名字 —— 前后端对同一个输入给出不同结论.
  const username = form.username.trim()
  if (!USERNAME_ALLOWED.test(username)) {
    return '用户名只能包含中文、字母、数字、下划线和连字符'
  }
  if (form.password !== form.confirmPassword) {
    return '两次密码输入不一致'
  }
  if (form.password.length < 8) {
    return '密码至少 8 位'
  }
  if (!PASSWORD_LETTER_AND_DIGIT.test(form.password)) {
    return '密码必须同时包含字母和数字'
  }
  return ''
}

async function handleRegister() {
  error.value = ''
  const problem = validate()
  if (problem) {
    error.value = problem
    return
  }
  loading.value = true
  try {
    const res = await register({
      username: form.username.trim(),
      password: form.password,
      email: form.email.trim()
    })
    if (res.data.code === 200) {
      // data 中已包含 token
      userStore.setUser(res.data.data)
      router.push('/')
    } else {
      error.value = res.data.message || '注册失败'
    }
  } catch (e) {
    error.value = e.response?.data?.message || '网络错误，请稍后重试'
  }
  loading.value = false
}
</script>
