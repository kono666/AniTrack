import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createMemoryHistory } from 'vue-router'

vi.mock('../../api', () => ({ register: vi.fn() }))

import Register from '../Register.vue'
import { register } from '../../api'

/**
 * 用户名的字符集校验(与后端 UsernamePolicy 是同一套规则).
 *
 * 改前前端只查了长度, 后端也只查了长度 —— 于是 "admin " (尾部一个空格) 能注册,
 * 它在评论列表、管理端用户表、Agent 的回答里与 "admin" 长得一模一样,
 * 而数据库里是两个不同的字符串. 这类账号是冒充的起点.
 *
 * 之所以前后端都要写: 后端那份是权威(绕不过去), 前端这份负责在提交前就把
 * 问题说清楚, 省掉一次往返. 两边规则必须一致 —— 不一致的话用户会遇到
 * "前端说可以、后端说不行"这种最让人恼火的组合.
 */

const router = createRouter({
  history: createMemoryHistory(),
  routes: [
    { path: '/', component: { template: '<div />' } },
    { path: '/register', component: { template: '<div />' } },
  ],
})

async function fill(username, password = 'abcd1234') {
  const wrapper = mount(Register, { global: { plugins: [router] } })
  await wrapper.find('input[type="text"]').setValue(username)
  await wrapper.find('input[type="email"]').setValue('a@x.com')
  const passwords = wrapper.findAll('input[type="password"]')
  await passwords[0].setValue(password)
  await passwords[1].setValue(password)
  await wrapper.find('form').trigger('submit')
  await flushPromises()
  return wrapper
}

describe('注册用户名字符集', () => {
  beforeEach(() => {
    localStorage.clear()
    setActivePinia(createPinia())
    vi.clearAllMocks()
    register.mockResolvedValue({ data: { code: 200, data: { id: 1, username: 'ok', token: 'jwt' } } })
  })

  it('中文/日文/字母数字下划线连字符都放行', async () => {
    for (const name of ['绫波丽', 'ゆきこ', 'アニメ好き', 'Ünïcödé', 'user_01', 'user-name', 'abc']) {
      vi.clearAllMocks()
      register.mockResolvedValue({ data: { code: 200, data: { id: 1, username: name, token: 'jwt' } } })
      const wrapper = await fill(name)
      expect(register).toHaveBeenCalled()
      expect(wrapper.find('.auth-error').exists()).toBe(false)
    }
  })

  it('含空格的用户名不提交, 直接给出可读提示', async () => {
    const wrapper = await fill('ad min')
    expect(register).not.toHaveBeenCalled()
    expect(wrapper.find('.auth-error').text()).toBe('用户名只能包含中文、字母、数字、下划线和连字符')
  })

  it('尾部带空格的用户名交给后端处理成合法名字, 不被前端拦下', async () => {
    // 提交前会 trim. 校验的必须是**将要发出去的那个值** ——
    // 否则用户在结尾多打一个空格就被拦, 而后端收到的本来就是一个合法名字,
    // 前后端对同一个输入给出不同结论
    const wrapper = await fill('  alice  ')
    expect(wrapper.find('.auth-error').exists()).toBe(false)
    expect(register).toHaveBeenCalledWith(expect.objectContaining({ username: 'alice' }))
  })

  it('含标点的用户名被拒', async () => {
    const wrapper = await fill('管理员!')
    expect(register).not.toHaveBeenCalled()
    expect(wrapper.find('.auth-error').exists()).toBe(true)
  })

  it('密码强度规则仍然生效(这一批改动没有把它挤掉)', async () => {
    const wrapper = await fill('alice', 'abcdefgh')
    expect(register).not.toHaveBeenCalled()
    expect(wrapper.find('.auth-error').text()).toBe('密码必须同时包含字母和数字')
  })
})
