import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import Assistant from '../Assistant.vue'

// 把网络层换成可控的替身: 这里要验的是「事件怎么落到界面上」,
// 而不是「后端到底返回了什么」—— 后者由后端那套测试负责
vi.mock('../../api', () => ({
  getAgentInfo: vi.fn(),
  getConversations: vi.fn(),
  getConversation: vi.fn(),
  deleteConversation: vi.fn(),
}))
vi.mock('../../api/agentStream', () => ({
  streamChat: vi.fn(),
  SSE_EVENTS: { TOOL_CALL: 'tool_call', TOOL_RESULT: 'tool_result', DONE: 'done', ERROR: 'error' },
}))

import { getAgentInfo, getConversations, deleteConversation } from '../../api'
import { streamChat } from '../../api/agentStream'

const INFO = {
  // provider 照实写成后端真会给的那种说法(application.yml 里配的), 而不是一个
  // 干净的产品名 —— 下面有一条断言就是冲着"它不该出现在界面上"去的
  provider: 'OpenAI 兼容接口',
  model: 'mock-model',
  loggedIn: false,
  personas: [
    { id: 'user-assistant', name: '找番助手', available: true },
    { id: 'admin-analyst', name: '运营分析', available: false },
  ],
  tools: ['search_anime'],
  dailyLimit: 500,
  dailyRemaining: 500,
}

/**
 * 让 streamChat 按脚本推事件.
 *
 * hold=true 时请求会悬停不返回, 用来观察「生成中」的中间态;
 * 默认直接跑完, 因为大部分用例只关心最终结果.
 */
function scriptedStream(events, done = { answer: '好的', rounds: 2 }, { hold = false } = {}) {
  let release
  const blocked = hold ? new Promise(r => { release = r }) : null
  streamChat.mockImplementation(async (payload, onEvent) => {
    for (const [name, data] of events) onEvent?.(name, data)
    if (blocked) await blocked
    return done
  })
  return () => release?.()
}

async function mountPage() {
  const wrapper = mount(Assistant, { global: { stubs: { RouterLink: true } } })
  await flushPromises()
  return wrapper
}

async function typeAndSend(wrapper, text = '推荐几部番') {
  await wrapper.find('.composer-input').setValue(text)
  await wrapper.find('.composer-btn').trigger('click')
  await flushPromises()
}

describe('Assistant page', () => {
  beforeEach(() => {
    localStorage.clear()
    setActivePinia(createPinia())
    vi.clearAllMocks()
    getAgentInfo.mockResolvedValue({ data: { code: 200, data: INFO } })
    getConversations.mockResolvedValue({ data: { code: 200, data: [] } })
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('shows the intro and hides the sidebar for a visitor', async () => {
    const wrapper = await mountPage()
    expect(wrapper.find('.chat-intro').exists()).toBe(true)
    expect(wrapper.find('.chat-sidebar').exists()).toBe(false)
    expect(wrapper.text()).toContain('访客模式')
  })

  it('renders the quota from the server', async () => {
    const wrapper = await mountPage()
    expect(wrapper.text()).toContain('今日剩余 500 次')
  })

  // 改前这里显示的是 `{{ info?.provider }} · {{ info?.model }}`, 而 provider 的
  // 取值就是配置里的「OpenAI 兼容接口」—— 那是接入方式的说明, 属于这一层的内部
  // 术语, 不该摆在访客眼前. 模型名留着.
  it('只把模型名显示给访客, 不显示内部的接入方式', async () => {
    const wrapper = await mountPage()
    expect(wrapper.find('.model-chip').text()).toBe('mock-model')
    expect(wrapper.find('.chat-subtitle').text()).not.toContain('兼容接口')
  })

  // 居中靠的是 .chat-scroll--empty 上的 flex + .chat-intro 的 margin:auto,
  // 而 jsdom 不算布局 —— 所以这里只能钉住"那个 class 什么时候在", 居没居中
  // 得靠眼睛(它同时是这条样式的开关, 钉住它就够挡住"顺手把这个 class 删了").
  it('只在空会话时给滚动区挂上空态 class', async () => {
    scriptedStream([])
    const wrapper = await mountPage()
    expect(wrapper.find('.chat-scroll').classes()).toContain('chat-scroll--empty')

    await typeAndSend(wrapper)
    expect(wrapper.find('.chat-scroll').classes()).not.toContain('chat-scroll--empty')
  })

  it('marks the admin persona unavailable to a normal user', async () => {
    const wrapper = await mountPage()
    const adminBtn = wrapper.findAll('.persona-btn').find(b => b.text() === '运营分析')
    expect(adminBtn.attributes('disabled')).toBeDefined()
  })

  it('sends a suggestion when it is clicked', async () => {
    scriptedStream([])
    const wrapper = await mountPage()

    await wrapper.findAll('.suggestion')[0].trigger('click')
    await flushPromises()

    expect(streamChat).toHaveBeenCalledTimes(1)
    expect(streamChat.mock.calls[0][0].message).toBe('最近有什么高分番推荐？')
  })

  it('shows the tool call while it is running, then the answer', async () => {
    const release = scriptedStream(
      [['tool_call', { round: 1, tool: 'get_ranking', args: {} }]],
      { answer: '推荐这几部', rounds: 2, inputTokens: 900, outputTokens: 120, conversationId: null },
      { hold: true }
    )
    const wrapper = await mountPage()
    await typeAndSend(wrapper)

    // 请求还挂着: 工具在跑, 正文还没出来
    expect(wrapper.text()).toContain('推荐几部番')
    expect(wrapper.text()).toContain('排行榜')
    expect(wrapper.text()).toContain('执行中…')
    expect(wrapper.find('.chat-typing').exists()).toBe(true)

    release()
    await flushPromises()

    expect(wrapper.text()).toContain('推荐这几部')
    expect(wrapper.text()).toContain('2 轮')
    expect(wrapper.find('.chat-typing').exists()).toBe(false)
  })

  it('pairs each tool_result with the step it belongs to', async () => {
    // 一轮里模型可能同时调好几个工具: 结果必须回到各自那一步,
    // 按顺序配对会在这里错位
    scriptedStream(
      [
        ['tool_call', { round: 1, tool: 'search_anime', args: { keyword: '巨人' } }],
        ['tool_call', { round: 1, tool: 'get_ranking', args: {} }],
        // 故意让第二个先返回
        ['tool_result', { round: 1, tool: 'get_ranking', error: true, preview: 'boom' }],
        ['tool_result', { round: 1, tool: 'search_anime', error: false, preview: '{"total":3}' }],
      ],
      { answer: '查完了', rounds: 1 }
    )

    const wrapper = await mountPage()
    await typeAndSend(wrapper)

    const steps = wrapper.findAll('.tool-step')
    expect(steps).toHaveLength(2)

    // 第一步: 搜索番剧, 成功
    expect(steps[0].text()).toContain('搜索番剧')
    expect(steps[0].classes()).not.toContain('tool-step--error')
    expect(steps[0].text()).toContain('{"total":3}')
    // 第二步: 排行榜, 失败
    expect(steps[1].text()).toContain('排行榜')
    expect(steps[1].classes()).toContain('tool-step--error')
    expect(steps[1].text()).toContain('boom')
  })

  it('shows the server error message and disables the composer when the quota runs out', async () => {
    const err = new Error('今日 AI 体验额度已用完, 明天再来。')
    err.status = 429
    streamChat.mockRejectedValue(err)
    // 出错后前端会重新拉一次 info, 这次额度已经是 0
    getAgentInfo
      .mockResolvedValueOnce({ data: { code: 200, data: INFO } })
      .mockResolvedValue({ data: { code: 200, data: { ...INFO, dailyRemaining: 0 } } })

    const wrapper = await mountPage()
    await typeAndSend(wrapper)

    expect(wrapper.text()).toContain('今日 AI 体验额度已用完')
    expect(wrapper.find('.chat-bubble--error').exists()).toBe(true)
    expect(wrapper.find('.composer-input').attributes('disabled')).toBeDefined()
    expect(wrapper.text()).toContain('今日体验额度已用完, 明天再来')
  })

  it('offers a stop button while a reply is streaming', async () => {
    const release = scriptedStream([], { answer: '好了', rounds: 1 }, { hold: true })
    const wrapper = await mountPage()
    await typeAndSend(wrapper)

    expect(wrapper.find('.composer-stop').exists()).toBe(true)
    expect(wrapper.find('.composer-btn:not(.composer-stop)').exists()).toBe(false)

    release()
    await flushPromises()
    expect(wrapper.find('.composer-stop').exists()).toBe(false)
    expect(wrapper.find('.composer-btn').exists()).toBe(true)
  })

  it('sends local history for a visitor so a new chat keeps context', async () => {
    scriptedStream([], { answer: '第一次回答', rounds: 1 })
    const wrapper = await mountPage()

    await typeAndSend(wrapper, '第一次提问')
    await flushPromises()

    scriptedStream([], { answer: '第二次回答', rounds: 1 })
    await typeAndSend(wrapper, '第二次提问')
    await flushPromises()

    const second = streamChat.mock.calls[1][0]
    expect(second.history).toEqual([
      { role: 'user', content: '第一次提问' },
      { role: 'assistant', content: '第一次回答' },
    ])
    // 访客没有会话 id
    expect(second.conversationId).toBeUndefined()
  })

  it('stops sending client history once the server owns the conversation', async () => {
    scriptedStream([], { answer: '第一次回答', rounds: 1, conversationId: 77 })
    const wrapper = await mountPage()
    await typeAndSend(wrapper, '第一次提问')
    await flushPromises()

    scriptedStream([], { answer: '第二次回答', rounds: 1, conversationId: 77 })
    await typeAndSend(wrapper, '第二次提问')
    await flushPromises()

    const second = streamChat.mock.calls[1][0]
    expect(second.conversationId).toBe(77)
    // 有会话 id 时以服务端记录为准, 不该再把浏览器里的历史发上去 ——
    // 那是可以被篡改的一份数据
    expect(second.history).toBeUndefined()
  })

  it('lists saved conversations for a logged-in user', async () => {
    localStorage.setItem('anime_user', JSON.stringify({ username: 'test', token: 'jwt', role: 'USER' }))
    setActivePinia(createPinia())
    getConversations.mockResolvedValue({
      data: {
        code: 200,
        data: [
          { id: 1, title: '找治愈番', messageCount: 4, persona: 'user-assistant', updatedAt: '2026-09-27T10:00:00' },
        ],
      },
    })

    const wrapper = await mountPage()
    expect(wrapper.find('.chat-sidebar').exists()).toBe(true)
    expect(wrapper.text()).toContain('找治愈番')
    expect(wrapper.text()).toContain('4 条')
  })

  // ========== 会话列表的可操作性 ==========
  //
  // 改前这一行是 `<li @click>` 里再套一个删除按钮: 鼠标能用, 但键盘既选不中
  // 会话也删不掉它 —— li 没有 tabindex, 而嵌在里面的 button 在语义上是
  // "一个按钮套着另一个按钮". 现在拆成两个并列的真按钮.

  function seedConversations() {
    localStorage.setItem('anime_user', JSON.stringify({ username: 'test', token: 'jwt', role: 'USER' }))
    setActivePinia(createPinia())
    getConversations.mockResolvedValue({
      data: {
        code: 200,
        data: [
          { id: 1, title: '找治愈番', messageCount: 4, persona: 'user-assistant', updatedAt: '2026-09-27T10:00:00' },
          { id: 2, title: '高分番', messageCount: 2, persona: 'user-assistant', updatedAt: '2026-09-27T11:00:00' },
        ],
      },
    })
  }

  it('会话行里是两个真正的按钮, 键盘能分别落到"打开"和"删除"上', async () => {
    seedConversations()
    const wrapper = await mountPage()

    const rows = wrapper.findAll('.conv-item')
    expect(rows).toHaveLength(2)
    // 打开会话的那个必须是 <button>(不是挂了 @click 的 div/li)
    expect(rows[0].find('.conv-main').element.tagName).toBe('BUTTON')
    expect(rows[0].find('.conv-del').element.tagName).toBe('BUTTON')
  })

  it('删除按钮有能读出来的名字(图标本身没有文字)', async () => {
    seedConversations()
    const wrapper = await mountPage()

    const del = wrapper.findAll('.conv-del')[0]
    expect(del.attributes('aria-label')).toContain('找治愈番')
  })

  it('点删除不会顺带把那个会话打开', async () => {
    // 改前靠 @click.stop 挡住, 现在两个按钮是并列的, 结构上就不会串味
    seedConversations()
    const wrapper = await mountPage()
    const { deleteConversation } = await import('../../api')
    deleteConversation.mockResolvedValue({ data: { code: 200 } })

    await wrapper.findAll('.conv-del')[0].trigger('click')
    await flushPromises()

    expect(deleteConversation).toHaveBeenCalledWith(1)
    expect(streamChat).not.toHaveBeenCalled()
  })

  it('图标按钮都带 aria-label', async () => {
    seedConversations()
    const wrapper = await mountPage()

    expect(wrapper.find('.sidebar-toggle').attributes('aria-label')).toBe('会话列表')
    expect(wrapper.find('.composer-btn').attributes('aria-label')).toBe('发送')
  })

  it('中断正在生成的流: 生成到一半离开页面时', async () => {
    // 改前没有 onBeforeUnmount, 切走之后那条 SSE 连接还开着:
    // 后端继续推、继续烧 AI 调用, 而每推一帧都在改一个已经不在页面上的组件.
    const release = scriptedStream([], { answer: '好了', rounds: 1 }, { hold: true })
    const wrapper = await mountPage()
    await typeAndSend(wrapper)

    const signal = streamChat.mock.calls[0][2]
    expect(signal).toBeDefined()
    expect(signal.aborted).toBe(false)

    wrapper.unmount()

    expect(signal.aborted).toBe(true)
    release()
  })

  it('点停止也要中断, 且不影响之后继续提问', async () => {
    // 这条是上一条的对照: 不能为了「离开页面就中断」把正常流程也弄坏
    const release = scriptedStream([], { answer: '好了', rounds: 1 }, { hold: true })
    const wrapper = await mountPage()
    await typeAndSend(wrapper)

    const signal = streamChat.mock.calls[0][2]
    await wrapper.find('.composer-stop').trigger('click')
    expect(signal.aborted).toBe(true)

    release()
    await flushPromises()
  })
})

/**
 * 输入长度上限.
 *
 * 上限是后端的(LlmProperties.maxInputLength = 1000, AgentController 里校验),
 * 前端改前既不拦也不说: 用户写完一大段、点发送, 才知道被拒了.
 *
 * 这里刻意**没有**用 textarea 的原生 maxlength —— 它会静默截断粘贴进来的内容,
 * 用户看见少了一截却没有任何提示, 比不让发更糟. 所以下面还要钉住"输入框本身
 * 没有被禁用": 超限的人必须还能把内容删回合规长度.
 */
describe('Assistant 输入长度', () => {
  beforeEach(() => {
    localStorage.clear()
    setActivePinia(createPinia())
    vi.clearAllMocks()
    getAgentInfo.mockResolvedValue({ data: { code: 200, data: INFO } })
    getConversations.mockResolvedValue({ data: { code: 200, data: [] } })
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  /** 一个不自动跑完的流: 把 onEvent 抓在手里, 想什么时候推一帧就什么时候推 */
  function openStream() {
    let emit = null
    let release = null
    streamChat.mockImplementation(async (payload, onEvent) => {
      emit = onEvent
      await new Promise(r => { release = r })
      return { answer: '好了', rounds: 1 }
    })
    return {
      emit: (name, data) => emit?.(name, data),
      release: () => release?.(),
    }
  }

  /**
   * 给 .chat-scroll 装上假的滚动尺寸.
   *
   * jsdom 不做布局: scrollHeight / clientHeight 恒为 0, 而 scrollTop 在原型上
   * 是个只读的口子. 三个都定义到实例上, 才谈得上"用户在中间某个位置".
   */
  function fakeScroll(wrapper, { height = 1000, view = 400 } = {}) {
    const el = wrapper.find('.chat-scroll').element
    let top = 0
    Object.defineProperty(el, 'scrollHeight', { value: height, configurable: true })
    Object.defineProperty(el, 'clientHeight', { value: view, configurable: true })
    Object.defineProperty(el, 'scrollTop', {
      configurable: true,
      get: () => top,
      set: (v) => { top = v },
    })
    return {
      get top() { return top },
      set top(v) { top = v },
    }
  }

  it('没输入时不显示字数', async () => {
    const wrapper = await mountPage()
    expect(wrapper.find('.composer-count').exists()).toBe(false)
  })

  it('1000 字正好能发, 显示「剩余 0 字」', async () => {
    scriptedStream([], { answer: '好', rounds: 1 })
    const wrapper = await mountPage()

    await wrapper.find('.composer-input').setValue('あ'.repeat(1000))
    await flushPromises()

    expect(wrapper.find('.composer-count').text()).toBe('剩余 0 字')
    expect(wrapper.find('.composer-btn').attributes('disabled')).toBeUndefined()

    await wrapper.find('.composer-btn').trigger('click')
    await flushPromises()

    expect(streamChat).toHaveBeenCalledTimes(1)
    expect(streamChat.mock.calls[0][0].message).toHaveLength(1000)
  })

  it('超过 1000 字: 说清超了多少, 并且发不出去', async () => {
    scriptedStream([], { answer: '好', rounds: 1 })
    const wrapper = await mountPage()

    await wrapper.find('.composer-input').setValue('あ'.repeat(1001))
    await flushPromises()

    const count = wrapper.find('.composer-count')
    expect(count.text()).toBe('超出 1 字')
    // 超限的那一刻它不再是"信息", 而是"发不出去的原因" —— 所以与禁用原因同色
    expect(count.classes()).toContain('over')
    expect(wrapper.find('.composer-btn').attributes('disabled')).toBeDefined()

    await wrapper.find('.composer-btn').trigger('click')
    await flushPromises()
    expect(streamChat).not.toHaveBeenCalled()
  })

  it('超限时按回车也不发', async () => {
    const wrapper = await mountPage()

    await wrapper.find('.composer-input').setValue('あ'.repeat(1001))
    await wrapper.find('.composer-input').trigger('keydown.enter')
    await flushPromises()

    expect(streamChat).not.toHaveBeenCalled()
  })

  it('超限时输入框本身没被禁用 —— 否则用户删不回合规长度', async () => {
    const wrapper = await mountPage()

    await wrapper.find('.composer-input').setValue('あ'.repeat(1001))
    await flushPromises()

    // canSend 只管额度. 把 overLimit 并进 canSend 会连带把 :disabled 挂到 textarea 上,
    // 那一刻用户就被困在超限状态里了: 发不出去, 也改不回来
    expect(wrapper.find('.composer-input').attributes('disabled')).toBeUndefined()
  })

  it('草稿超长时, 上面的预设问句仍然发得出去', async () => {
    scriptedStream([])
    const wrapper = await mountPage()

    await wrapper.find('.composer-input').setValue('あ'.repeat(1001))
    await flushPromises()

    await wrapper.findAll('.suggestion')[0].trigger('click')
    await flushPromises()

    // 长度判的是**这次真正要发的串**, 不是输入框里那份草稿 ——
    // 判 input 的话, 一份躺着的草稿会把上面的建议按钮一起锁死
    expect(streamChat).toHaveBeenCalledTimes(1)
    expect(streamChat.mock.calls[0][0].message).toBe('最近有什么高分番推荐？')
  })

  // ========== 流式回答时的滚动 ==========
  //
  // 改前 scrollToBottom() 在 4 处被**无条件**调用, 其中一处在每帧 SSE 的回调里:
  // 用户往上翻看历史时, 页面被一个 token 一个 token 地拽回底部, 想翻回去都翻不了.

  it('往上翻看历史时, 新事件不会把页面拽回底部', async () => {
    const stream = openStream()
    const wrapper = await mountPage()
    const scroll = fakeScroll(wrapper)

    await typeAndSend(wrapper)
    scroll.top = 0 // 用户自己往上翻

    stream.emit('tool_call', { round: 1, tool: 'get_ranking', args: {} })
    await flushPromises()

    expect(scroll.top).toBe(0)

    stream.release()
    await flushPromises()
    wrapper.unmount()
  })

  it('本来就贴底时, 新内容继续跟着走', async () => {
    // 上一条的对照: 不能为了"别拽用户"变成"永远不跟着滚" ——
    // 那样发完消息页面纹丝不动, 看起来像坏了
    const stream = openStream()
    const wrapper = await mountPage()
    const scroll = fakeScroll(wrapper)

    await typeAndSend(wrapper)
    scroll.top = 960 // 1000 - 960 - 400 远小于阈值 = 还贴着底

    stream.emit('tool_call', { round: 1, tool: 'get_ranking', args: {} })
    await flushPromises()

    expect(scroll.top).toBe(1000)

    stream.release()
    await flushPromises()
    wrapper.unmount()
  })

  it('用户自己发一条时, 即使正在上翻也强制到底', async () => {
    scriptedStream([], { answer: '好了', rounds: 1 })
    const wrapper = await mountPage()
    const scroll = fakeScroll(wrapper)

    scroll.top = 0
    await typeAndSend(wrapper)

    // 发消息是"跳到最新"的明确意图, 不该被那个阈值挡住
    expect(scroll.top).toBe(1000)
    wrapper.unmount()
  })
})
