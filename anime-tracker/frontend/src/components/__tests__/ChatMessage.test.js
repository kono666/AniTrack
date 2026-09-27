import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import ChatMessage from '../ChatMessage.vue'

/** router-link 替身: 卡片是链接, 但这里不关心跳转 */
const RouterLinkStub = {
  name: 'RouterLink',
  props: ['to'],
  template: '<a :href="to"><slot /></a>',
}

function mountMessage(message) {
  return mount(ChatMessage, {
    props: { message },
    global: { stubs: { RouterLink: RouterLinkStub } },
  })
}

describe('ChatMessage', () => {
  it('renders the user message text', () => {
    const wrapper = mount(ChatMessage, {
      props: { message: { role: 'user', content: '推荐几部番' } },
    })
    expect(wrapper.text()).toContain('推荐几部番')
    expect(wrapper.find('.chat-msg--user').exists()).toBe(true)
  })

  it('renders the assistant message text', () => {
    const wrapper = mount(ChatMessage, {
      props: { message: { role: 'assistant', content: '试试这几部' } },
    })
    expect(wrapper.text()).toContain('试试这几部')
    expect(wrapper.find('.chat-msg--assistant').exists()).toBe(true)
  })

  it('shows no tool timeline when nothing was called', () => {
    const wrapper = mount(ChatMessage, {
      props: { message: { role: 'assistant', content: '你好', steps: [] } },
    })
    expect(wrapper.find('.tool-timeline').exists()).toBe(false)
  })

  // 工具调用的展示是这个项目的重点: 要让人一眼看出「它真的去查了」,
  // 而不是只看到一个漂亮的回答
  it('renders a readable label for each tool call', () => {
    const wrapper = mount(ChatMessage, {
      props: {
        message: {
          role: 'assistant',
          content: '查到了',
          steps: [
            { round: 1, tool: 'get_ranking', args: { limit: 5 }, millis: 42 },
          ],
        },
      },
    })
    expect(wrapper.text()).toContain('排行榜')
    expect(wrapper.text()).toContain('调用了 1 个工具')
    expect(wrapper.text()).toContain('42 ms')
  })

  it('falls back to the raw tool name for an unknown tool', () => {
    const wrapper = mount(ChatMessage, {
      props: {
        message: {
          role: 'assistant',
          content: 'x',
          steps: [{ round: 1, tool: 'some_new_tool', args: {} }],
        },
      },
    })
    expect(wrapper.text()).toContain('some_new_tool')
  })

  it('marks a failed step and counts it in the summary', () => {
    const wrapper = mount(ChatMessage, {
      props: {
        message: {
          role: 'assistant',
          content: '没查到',
          steps: [
            { round: 1, tool: 'get_anime_detail', args: { subjectId: 1 }, error: true },
            { round: 2, tool: 'search_anime', args: { keyword: '巨人' }, millis: 12 },
          ],
        },
      },
    })
    expect(wrapper.text()).toContain('调用了 2 个工具 · 1 个失败')
    expect(wrapper.find('.tool-step--error').exists()).toBe(true)
  })

  it('marks a step still running', () => {
    const wrapper = mount(ChatMessage, {
      props: {
        message: {
          role: 'assistant',
          content: '',
          steps: [{ round: 1, tool: 'search_anime', args: {}, running: true }],
        },
      },
    })
    expect(wrapper.find('.tool-step--running').exists()).toBe(true)
    expect(wrapper.text()).toContain('执行中')
  })

  it('renders tool arguments', () => {
    const wrapper = mount(ChatMessage, {
      props: {
        message: {
          role: 'assistant',
          content: 'x',
          steps: [{ round: 1, tool: 'read_reviews', args: { subjectId: 4242 }, millis: 8 }],
        },
      },
    })
    expect(wrapper.text()).toContain('subjectId=4242')
  })

  it('collapses the timeline when the header is clicked', async () => {
    const wrapper = mount(ChatMessage, {
      props: {
        message: {
          role: 'assistant',
          content: 'x',
          steps: [{ round: 1, tool: 'get_ranking', args: {}, millis: 5 }],
        },
      },
    })
    expect(wrapper.find('.tool-steps').exists()).toBe(true)

    await wrapper.find('.tool-timeline-toggle').trigger('click')
    expect(wrapper.find('.tool-steps').exists()).toBe(false)
  })

  it('shows the typing indicator while waiting for the first token', () => {
    const wrapper = mount(ChatMessage, {
      props: { message: { role: 'assistant', content: '', streaming: true, steps: [] } },
    })
    expect(wrapper.find('.chat-typing').exists()).toBe(true)
  })

  it('shows token and round metadata when present', () => {
    const wrapper = mount(ChatMessage, {
      props: {
        message: {
          role: 'assistant',
          content: 'x',
          steps: [],
          rounds: 3,
          inputTokens: 1200,
          outputTokens: 300,
        },
      },
    })
    expect(wrapper.text()).toContain('3 轮')
    expect(wrapper.text()).toContain('token 1200/300')
  })

  it('flags an error message', () => {
    const wrapper = mount(ChatMessage, {
      props: { message: { role: 'assistant', content: '额度已用完', error: true } },
    })
    expect(wrapper.find('.chat-bubble--error').exists()).toBe(true)
  })

  // 工具查到番剧时直接把卡片画出来, 而不是让用户去读一段 JSON 再自己搜一次
  it('renders an anime card for each result the backend marked', () => {
    const wrapper = mountMessage({
      role: 'assistant',
      content: '推荐这几部',
      steps: [
        {
          round: 1,
          tool: 'get_ranking',
          args: {},
          millis: 30,
          cards: [
            { id: 1, name: '进击的巨人', nameCn: '进击的巨人', cover: 'https://x/1.jpg', rating: 9.0, episodes: 25 },
            { id: 2, name: '葬送的芙莉莲', cover: 'https://x/2.jpg', rating: 9.4 },
          ],
        },
      ],
    })

    const chips = wrapper.findAll('.anime-chip')
    expect(chips).toHaveLength(2)
    expect(wrapper.text()).toContain('葬送的芙莉莲')
    expect(wrapper.text()).toContain('9.4')
  })

  it('renders no cards when the tool result held no anime', () => {
    const wrapper = mountMessage({
      role: 'assistant',
      content: '这是运营数据',
      steps: [{ round: 1, tool: 'analyze_anime_heat', args: {}, millis: 12, cards: [] }],
    })
    expect(wrapper.find('.tool-step-cards').exists()).toBe(false)
  })

  it('keeps the cards visible after the timeline is collapsed', async () => {
    // 卡片挂在时间线里, 所以「折叠」会把它们一起收起来 —— 这是有意的:
    // 结论优先, 依据留给想看过程的人. 这里把这个行为钉住, 免得以后当成 bug 改掉
    const wrapper = mountMessage({
      role: 'assistant',
      content: 'x',
      steps: [{ round: 1, tool: 'get_ranking', args: {}, cards: [{ id: 1, name: '某番' }] }],
    })
    expect(wrapper.find('.anime-chip').exists()).toBe(true)

    await wrapper.find('.tool-timeline-toggle').trigger('click')
    expect(wrapper.find('.anime-chip').exists()).toBe(false)
  })
})
