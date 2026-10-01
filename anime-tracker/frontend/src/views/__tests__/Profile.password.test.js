import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createMemoryHistory } from 'vue-router'

const { toastSpy } = vi.hoisted(() => ({ toastSpy: vi.fn() }))

vi.mock('../../api', () => ({
  getTrackingList: vi.fn(),
  getOverallStats: vi.fn(),
  saveTracking: vi.fn(),
  getNotifications: vi.fn(),
  markNotificationsRead: vi.fn(),
  // 整体替换模块, 漏一个就在解构时抛 —— 这六个是 Profile.vue 会全部用到的
  changePassword: vi.fn(),
}))

vi.mock('../../composables/useToast', () => ({
  useToast: () => ({ show: toastSpy, remove: vi.fn(), items: { value: [] } }),
  showToast: toastSpy,
}))

import Profile from '../Profile.vue'
import { useUserStore } from '../../stores/user'
import { getTrackingList, getOverallStats, saveTracking, getNotifications, changePassword } from '../../api'

/**
 * 个人页「账号安全」这一块 —— 用户改自己的密码.
 *
 * 这一组钉的是**前端唯一会写密码的地方**, 所以三件事同等重要:
 *
 * 1. **校验在发请求之前**. 五种填错各有各的说法, 而它们全都是 0 次往返就能判出来的.
 *    让它们走到服务端的话, 用户要多等一个来回才看到"两次密码不一致".
 * 2. **成功之后必须把服务端回的那张新 token 存回去**. 改密会让改密之前签发的 token
 *    全部作废, 而手上这张正在其中 —— 不换, 下一个请求就是 401, 用户看到的是
 *    「我刚改完密码就被登出了」. 这条断言是那行 `userStore.setUser` 唯一的哨兵.
 * 3. **失败要把用户填的内容留在格子里**. 密码错一次就清空三格的话, 用户得从头再输
 *    一遍, 而服务端的文案("原密码不正确")本身是准确的, 直接显示它最好.
 */

const router = createRouter({
  history: createMemoryHistory(),
  routes: [
    { path: '/', component: { template: '<div />' } },
    { path: '/login', component: { template: '<div />' } },
  ],
})

const ME = { id: 1, username: 'alice', token: 'jwt-old', role: 'USER', avatar: null, email: 'a@example.com' }

/** 服务端改密成功时的载荷: 除了新 token, 其余字段原样带回 */
const NEW_ME = { ...ME, token: 'jwt-new' }

async function mountProfile() {
  localStorage.setItem('anime_user', JSON.stringify(ME))
  setActivePinia(createPinia())
  const store = useUserStore()
  await router.push('/')
  await router.isReady()

  const wrapper = mount(Profile, { global: { plugins: [router] } })
  await flushPromises()
  return { wrapper, store }
}

const oldBox = (w) => w.find('#sec-old')
const newBox = (w) => w.find('#sec-new')
const confirmBox = (w) => w.find('#sec-confirm')
const submitBtn = (w) => w.find('.sec-submit')
const errorText = (w) => w.find('.sec-error').exists() ? w.find('.sec-error').text() : ''

/** 把三格填上再提交 */
async function fillAndSubmit(w, oldPwd, newPwd, confirm = newPwd) {
  await oldBox(w).setValue(oldPwd)
  await newBox(w).setValue(newPwd)
  await confirmBox(w).setValue(confirm)
  await submitBtn(w).trigger('submit')
  await flushPromises()
}

beforeEach(() => {
  localStorage.clear()
  vi.clearAllMocks()
  getTrackingList.mockResolvedValue({ data: { code: 200, data: [] } })
  getOverallStats.mockResolvedValue({
    data: { code: 200, data: { totalAnime: 0, totalEpisodes: 0, totalReviews: 0, avgScore: 0, completed: 0 } },
  })
  saveTracking.mockResolvedValue({ data: { code: 200, data: { id: 1 } } })
  // 通知那一块默认给空 —— 这几条用例测的是改密码, 不关心它(它的失败也影响不到
  // 这一块: 两个区块各自独立)
  getNotifications.mockResolvedValue({ data: { code: 200, data: { list: [], total: 0 } } })
})

describe('个人页改密码: 五道本地校验, 一个请求都不发', () => {
  // 这一组全部断言"没有发请求": 校验做在提交之前才是校验, 做在回来之后只是错误提示
  const CASES = [
    ['原密码空着', '', 'brandnew1', 'brandnew1', '请输入原密码'],
    ['两次新密码不一致', 'oldpass1', 'brandnew1', 'brandnew2', '两次密码输入不一致'],
    ['新密码太短', 'oldpass1', 'short1', 'short1', '密码至少 8 位'],
    ['新密码只有字母', 'oldpass1', 'abcdefgh', 'abcdefgh', '密码必须同时包含字母和数字'],
    ['新密码与原密码相同', 'oldpass1', 'oldpass1', 'oldpass1', '新密码不能与原密码相同'],
  ]

  for (const [name, oldPwd, newPwd, confirm, message] of CASES) {
    it(`${name} → 「${message}」`, async () => {
      const { wrapper } = await mountProfile()

      await fillAndSubmit(wrapper, oldPwd, newPwd, confirm)

      expect(errorText(wrapper)).toBe(message)
      expect(changePassword).not.toHaveBeenCalled()
    })
  }
})

describe('个人页改密码: 成功', () => {
  it('带上原密码与新密码, 存下服务端回的新 token, 清空三格并提示', async () => {
    changePassword.mockResolvedValue({ data: { code: 200, data: NEW_ME } })
    const { wrapper, store } = await mountProfile()
    expect(store.token).toBe('jwt-old')

    await fillAndSubmit(wrapper, 'oldpass1', 'brandnew1')

    expect(changePassword).toHaveBeenCalledWith({
      oldPassword: 'oldpass1', newPassword: 'brandnew1',
    })
    // 这一行是重点: 旧 token 此刻已经作废了, 界面手上必须是新的那张
    expect(store.token).toBe('jwt-new')
    expect(JSON.parse(localStorage.getItem('anime_user')).token).toBe('jwt-new')
    expect(toastSpy).toHaveBeenCalledWith('密码已修改，其它设备需重新登录', 'success')
    // 明文密码不该在 DOM 里多留一秒
    expect(oldBox(wrapper).element.value).toBe('')
    expect(newBox(wrapper).element.value).toBe('')
    expect(confirmBox(wrapper).element.value).toBe('')
    expect(errorText(wrapper)).toBe('')
  })

  it('提交中按钮禁用, 不会因为连点发出两次', async () => {
    let release
    changePassword.mockReturnValue(new Promise((res) => { release = res }))
    const { wrapper } = await mountProfile()

    await oldBox(wrapper).setValue('oldpass1')
    await newBox(wrapper).setValue('brandnew1')
    await confirmBox(wrapper).setValue('brandnew1')
    await submitBtn(wrapper).trigger('submit')
    await wrapper.vm.$nextTick()

    expect(submitBtn(wrapper).attributes('disabled')).toBeDefined()
    expect(submitBtn(wrapper).text()).toBe('提交中...')

    release({ data: { code: 200, data: NEW_ME } })
    await flushPromises()
    expect(changePassword).toHaveBeenCalledTimes(1)
  })
})

describe('个人页改密码: 服务端拒绝', () => {
  it('显示服务端的原话, 并保留用户已经填好的内容', async () => {
    changePassword.mockRejectedValue({ response: { data: { message: '原密码不正确' } } })
    const { wrapper, store } = await mountProfile()

    await fillAndSubmit(wrapper, 'typo-here', 'brandnew1')

    expect(errorText(wrapper)).toBe('原密码不正确')
    expect(store.token).toBe('jwt-old')
    // 清空的话用户得把三格重打一遍, 而错的通常只有第一格
    expect(oldBox(wrapper).element.value).toBe('typo-here')
    expect(newBox(wrapper).element.value).toBe('brandnew1')
  })

  it('连接断开(没有 response)时给兜底文案, 不是 undefined', async () => {
    changePassword.mockRejectedValue(new Error('Network Error'))
    const { wrapper } = await mountProfile()

    await fillAndSubmit(wrapper, 'oldpass1', 'brandnew1')

    expect(errorText(wrapper)).toBe('修改失败，请稍后重试')
  })
})
