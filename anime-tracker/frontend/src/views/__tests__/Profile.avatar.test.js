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
  changePassword: vi.fn(),
  // 整体替换模块, 漏一个就在解构时抛
  uploadAvatar: vi.fn(),
  deleteAvatar: vi.fn(),
}))

vi.mock('../../composables/useToast', () => ({
  useToast: () => ({ show: toastSpy, remove: vi.fn(), items: { value: [] } }),
  showToast: toastSpy,
}))

import Profile from '../Profile.vue'
import { useUserStore } from '../../stores/user'
import {
  getTrackingList, getOverallStats, getNotifications, uploadAvatar, deleteAvatar,
} from '../../api'

/**
 * 个人页的头像上传.
 *
 * 三件事同等重要, 而且是三件不同的事:
 *
 * 1. **浏览器侧那两道校验是"省一次往返", 不是防线** —— 所以用例断的是"提示出现了"
 *    和"**没有发请求**"。只断提示的话, 一个"先发请求、失败了再提示"的实现在这里
 *    照样全绿, 而那正是要避免的形状: 传一张 5MB 的图上去等它被拒, 体验差得远。
 *    真正的防线在服务端, 本仓那一边由 `AvatarServiceTest` 守着。
 * 2. **上传成功后要把服务端回的地址写回 store**。不写的话页面上的头像不变(要等下一次
 *    `/api/user/me`), 而用户看到的是"上传成功了但没变"。
 * 3. **写回是"合并"而不是"整份替换"**。`setUser({ avatar })` 会把 token 一起抹掉 ——
 *    表现是"换个头像就被登出了"。所以这里同时断 token 还在、以及 localStorage 里
 *    那一份也更新了(少了它, 刷新之后头像又变回去)。
 *
 * 那个 `?v=` 版本号也是断言的一部分: 它是缓存失效的唯一机制。服务端回了带版本号的
 * 地址、而前端把它截掉了的话, 用户刚上传完看到的还是旧头像。
 */

const router = createRouter({
  history: createMemoryHistory(),
  routes: [
    { path: '/', component: { template: '<div />' } },
    { path: '/login', component: { template: '<div />' } },
  ],
})

const ME = { id: 1, username: 'alice', token: 'jwt', role: 'USER', avatar: null, email: 'a@example.com' }
const NEW_URL = '/api/user/1/avatar?v=1893456000000'

const ok = data => ({ data: { code: 200, data } })

async function mountProfile(me = ME) {
  localStorage.setItem('anime_user', JSON.stringify(me))
  setActivePinia(createPinia())
  const store = useUserStore()
  await router.push('/')
  await router.isReady()

  const wrapper = mount(Profile, { global: { plugins: [router] } })
  await flushPromises()
  return { wrapper, store }
}

/** 造一个 File. jsdom 认它, size 由内容算出来 */
function makeFile(name, type, bytes = 32) {
  return new File([new Uint8Array(bytes)], name, { type })
}

/**
 * 模拟"在系统文件框里选了一个文件"。
 *
 * jsdom 里 `input.files` 是只读的, 所以只能 defineProperty 顶掉它 —— 这是测文件上传
 * 唯一的路子(`setValue` 对 file input 不起作用)。随后 trigger('change') 走的是组件
 * 真的那个 @change 处理器。
 *
 * ⚠️ **`value` 也要一起顶掉, 而且要给一个非空值。** 真浏览器里选完文件之后它是
 * `C:\fakepath\a.png` 这样一段串 —— 而"选同一个文件第二次不触发 change"正是因为
 * 这个值没变。jsdom 里 file input 的 value 天然是 `''`, 于是「组件把那行清空了吗」
 * 这条断言会退化成 `'' === ''`: 把组件里那句 `event.target.value = ''` 删掉, 用例
 * **照样全绿**(实测过)。那不是哨兵, 那是一句永远成立的话。
 */
async function pick(wrapper, file) {
  const input = wrapper.find('.pa-file')
  Object.defineProperty(input.element, 'files', { value: [file], configurable: true })
  Object.defineProperty(input.element, 'value', {
    value: 'C:\\fakepath\\' + file.name,
    configurable: true,
    writable: true,
  })
  await input.trigger('change')
  await flushPromises()
  return input
}

const avatarImg = w => w.find('.p-avatar-img')
const uploadBtn = w => w.find('.p-avatar-actions .pa-btn')
const deleteBtn = w => w.find('.pa-btn-muted')
const storedUser = () => JSON.parse(localStorage.getItem('anime_user'))

beforeEach(() => {
  localStorage.clear()
  vi.clearAllMocks()
  getTrackingList.mockResolvedValue(ok([]))
  getOverallStats.mockResolvedValue(ok({ totalAnime: 0, totalEpisodes: 0, totalReviews: 0, avgScore: 0 }))
  getNotifications.mockResolvedValue(ok({ list: [], total: 0 }))
  uploadAvatar.mockResolvedValue(ok({ avatar: NEW_URL }))
  deleteAvatar.mockResolvedValue(ok(null))
})

describe('个人页的头像', () => {
  it('没传过头像时: 显示图标, 按钮写「上传头像」', async () => {
    const { wrapper } = await mountProfile()

    expect(avatarImg(wrapper).exists()).toBe(false)
    expect(uploadBtn(wrapper).text()).toBe('上传头像')
  })

  it('有头像时: 显示图片, 按钮写「更换」并多出一个「删除」', async () => {
    const { wrapper } = await mountProfile({ ...ME, avatar: NEW_URL })

    expect(avatarImg(wrapper).attributes('src')).toBe(NEW_URL)
    expect(uploadBtn(wrapper).text()).toBe('更换')
    expect(deleteBtn(wrapper).exists()).toBe(true)
  })

  it('选一张 png: 发请求, 头像换成服务端回的地址(带版本号)', async () => {
    const { wrapper } = await mountProfile()

    const file = makeFile('a.png', 'image/png')
    await pick(wrapper, file)

    expect(uploadAvatar).toHaveBeenCalledWith(file)
    expect(avatarImg(wrapper).attributes('src')).toBe(NEW_URL)
    expect(uploadBtn(wrapper).text()).toBe('更换')
    expect(toastSpy).toHaveBeenCalledWith('头像已更新', 'success')
  })

  it('上传成功后: token 还在(是合并不是整份替换), 且 localStorage 里的那份也更新了', async () => {
    const { wrapper } = await mountProfile()

    await pick(wrapper, makeFile('a.png', 'image/png'))

    // 「换个头像就被登出了」是整份替换的症状 —— 这条就是那个的哨兵
    expect(storedUser().token).toBe('jwt')
    expect(storedUser().username).toBe('alice')
    // 少了这一行, 用户会看到"传完了、一刷新又变回去了"
    expect(storedUser().avatar).toBe(NEW_URL)
  })

  it('超过 512KB: 当场提示, 且**一次请求都不发**', async () => {
    const { wrapper } = await mountProfile()

    await pick(wrapper, makeFile('big.png', 'image/png', 600 * 1024))

    expect(uploadAvatar).not.toHaveBeenCalled()
    expect(toastSpy).toHaveBeenCalledWith('图片不能超过 512KB', 'error')
    expect(avatarImg(wrapper).exists()).toBe(false)
  })

  it('类型不在白名单(webp): 当场提示, 且**一次请求都不发**', async () => {
    const { wrapper } = await mountProfile()

    await pick(wrapper, makeFile('a.webp', 'image/webp'))

    expect(uploadAvatar).not.toHaveBeenCalled()
    expect(toastSpy).toHaveBeenCalledWith('只支持 PNG 或 JPEG 格式的图片', 'error')
  })

  it('选完文件后把 input 的值清掉 —— 同一个文件选第二次仍然会触发', async () => {
    const { wrapper } = await mountProfile()

    const input = await pick(wrapper, makeFile('a.png', 'image/png'))

    // 不清的话「值没变 ⇒ 不触发 change」, 用户看到的是"点了没反应" ——
    // 而重试一次正是这里最常见的动作(第一次上传失败之后)
    expect(input.element.value).toBe('')
  })

  it('上传失败: 显示服务端的原话(尺寸/格式那些只有它知道)', async () => {
    uploadAvatar.mockRejectedValue({
      response: { data: { message: '图片尺寸不能超过 2048×2048 像素' } },
    })
    const { wrapper } = await mountProfile()

    await pick(wrapper, makeFile('a.png', 'image/png'))

    expect(toastSpy).toHaveBeenCalledWith('图片尺寸不能超过 2048×2048 像素', 'error')
    // 失败时不该把 URL 写成什么都不是的东西
    expect(avatarImg(wrapper).exists()).toBe(false)
  })

  it('没有 message 时给一句兜底, 而不是 undefined', async () => {
    uploadAvatar.mockRejectedValue(new Error('boom'))
    const { wrapper } = await mountProfile()

    await pick(wrapper, makeFile('a.png', 'image/png'))

    expect(toastSpy).toHaveBeenCalledWith('头像上传失败，请重试', 'error')
  })

  it('删除: 调接口, 退回图标, localStorage 里的 avatar 也变回 null', async () => {
    const { wrapper } = await mountProfile({ ...ME, avatar: NEW_URL })

    await deleteBtn(wrapper).trigger('click')
    await flushPromises()

    expect(deleteAvatar).toHaveBeenCalled()
    expect(avatarImg(wrapper).exists()).toBe(false)
    expect(uploadBtn(wrapper).text()).toBe('上传头像')
    expect(storedUser().avatar).toBeNull()
    expect(storedUser().token).toBe('jwt')
  })

  it('图片挂了(URL 在但取不到): 退回图标, 而不是一个破图标记', async () => {
    const { wrapper } = await mountProfile({ ...ME, avatar: '/api/user/1/avatar?v=dead' })

    await avatarImg(wrapper).trigger('error')

    // 这一格原本只是"没有头像"而已, 不该变成一个破图
    expect(avatarImg(wrapper).exists()).toBe(false)
    expect(wrapper.find('.p-avatar svg').exists()).toBe(true)
  })

  it('换一张能读的图之后, 上一次的"破图"标记要清掉', async () => {
    const { wrapper } = await mountProfile({ ...ME, avatar: '/api/user/1/avatar?v=dead' })
    await avatarImg(wrapper).trigger('error')

    await pick(wrapper, makeFile('a.png', 'image/png'))

    // 新图明明能读, 这一格却还显示图标 —— 那是"修不回来"的状态
    expect(avatarImg(wrapper).exists()).toBe(true)
  })
})
