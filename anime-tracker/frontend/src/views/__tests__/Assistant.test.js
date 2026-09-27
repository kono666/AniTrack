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

import { getAgentInfo, getConversations } from '../../api'
import { streamChat } from '../../api/agentStream'

const INFO = {
  provider: 'Mock',
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
})
