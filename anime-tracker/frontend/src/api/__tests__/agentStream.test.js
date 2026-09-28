import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { parseSseBuffer, streamChat, SSE_EVENTS } from '../agentStream'

/**
 * 手写 SSE 解析最容易错的地方是分片边界 —— 一个 HTTP 分片里可能有半帧、
 * 也可能有三帧半. 这类问题在真实网络里偶发, 点页面基本复现不出来,
 * 所以用纯函数直接测.
 */
describe('parseSseBuffer', () => {
  it('parses a single complete frame', () => {
    const { events, rest } = parseSseBuffer(
      'event:tool_call\ndata:{"tool":"get_ranking"}\n\n'
    )
    expect(events).toEqual([{ name: 'tool_call', data: '{"tool":"get_ranking"}' }])
    expect(rest).toBe('')
  })

  it('parses several frames in one chunk', () => {
    const { events } = parseSseBuffer(
      'event:tool_call\ndata:{"a":1}\n\n' +
      'event:tool_result\ndata:{"b":2}\n\n' +
      'event:done\ndata:{"answer":"hi"}\n\n'
    )
    expect(events.map(e => e.name)).toEqual(['tool_call', 'tool_result', 'done'])
    expect(events[2].data).toBe('{"answer":"hi"}')
  })

  it('keeps an incomplete trailing frame in the buffer', () => {
    const { events, rest } = parseSseBuffer(
      'event:tool_call\ndata:{"round":1}\n\nevent:tool_result\ndata:{"rou'
    )
    expect(events).toHaveLength(1)
    expect(rest).toBe('event:tool_result\ndata:{"rou')
  })

  it('does not emit the frame until it is complete', () => {
    // 模拟一次真实的分片错位: 帧头和数据被切在两个分片里
    const first = parseSseBuffer('event:done\ndata:{"ans')
    expect(first.events).toHaveLength(0)
    expect(first.rest).toBe('event:done\ndata:{"ans')

    const second = parseSseBuffer(first.rest + 'wer":"你好","rounds":2}\n\n')
    expect(second.events).toHaveLength(1)
    expect(second.events[0].name).toBe('done')
    expect(JSON.parse(second.events[0].data).answer).toBe('你好')
  })

  it('handles CRLF line endings', () => {
    const { events } = parseSseBuffer('event:done\r\ndata:{"a":1}\r\n\r\n')
    expect(events).toEqual([{ name: 'done', data: '{"a":1}' }])
  })

  it('joins multiple data lines with a newline', () => {
    const { events } = parseSseBuffer('event:done\ndata:line1\ndata:line2\n\n')
    expect(events[0].data).toBe('line1\nline2')
  })

  it('falls back to the default event name', () => {
    const { events } = parseSseBuffer('data:hello\n\n')
    expect(events[0].name).toBe('message')
  })

  it('skips blank frames and ignores unknown fields', () => {
    const { events } = parseSseBuffer(
      '\n\nid:7\nretry:3000\nevent:done\ndata:{"a":1}\n\n\n\n'
    )
    expect(events).toHaveLength(1)
    expect(events[0].name).toBe('done')
  })

  it('returns nothing for an empty buffer', () => {
    expect(parseSseBuffer('')).toEqual({ events: [], rest: '' })
  })
})

/** 把若干分片拼成一个假的 fetch 响应 */
function mockFetch(chunks, { ok = true, status = 200, json = null } = {}) {
  const encoder = new TextEncoder()
  return vi.fn().mockResolvedValue({
    ok,
    status,
    json: async () => json,
    body: ok
      ? new ReadableStream({
          start(controller) {
            for (const c of chunks) controller.enqueue(encoder.encode(c))
            controller.close()
          },
        })
      : null,
  })
}

describe('streamChat', () => {
  beforeEach(() => {
    localStorage.clear()
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('emits every event and resolves with the done payload', async () => {
    global.fetch = mockFetch([
      'event:tool_call\ndata:{"round":1,"tool":"get_ranking","args":{}}\n\n',
      'event:tool_result\ndata:{"round":1,"tool":"get_ranking","error":false,"preview":"{}"}\n\n',
      'event:done\ndata:{"answer":"推荐这几部","rounds":2,"conversationId":9}\n\n',
    ])

    const seen = []
    const done = await streamChat({ message: 'hi' }, (name, data) => seen.push([name, data]))

    expect(seen.map(s => s[0])).toEqual(['tool_call', 'tool_result', 'done'])
    expect(seen[0][1].tool).toBe('get_ranking')
    expect(done.answer).toBe('推荐这几部')
    expect(done.conversationId).toBe(9)
  })

  it('reassembles an event split across chunks', async () => {
    global.fetch = mockFetch([
      'event:do',
      'ne\ndata:{"ans',
      'wer":"跨分片的回答"}\n\n',
    ])

    const names = []
    const done = await streamChat({ message: 'hi' }, n => names.push(n))

    expect(names).toEqual(['done'])
    expect(done.answer).toBe('跨分片的回答')
  })

  it('sends the bearer token and the request body', async () => {
    localStorage.setItem('anime_user', JSON.stringify({ username: 'u', token: 'jwt-abc' }))
    global.fetch = mockFetch(['event:done\ndata:{"answer":"ok"}\n\n'])

    await streamChat({ message: '你好', persona: 'user-assistant' })

    const [url, opts] = global.fetch.mock.calls[0]
    expect(url).toBe('/api/agent/chat/stream')
    expect(opts.method).toBe('POST')
    expect(opts.headers.Authorization).toBe('Bearer jwt-abc')
    expect(JSON.parse(opts.body)).toEqual({ message: '你好', persona: 'user-assistant' })
  })

  it('omits the Authorization header for anonymous visitors', async () => {
    global.fetch = mockFetch(['event:done\ndata:{"answer":"ok"}\n\n'])

    await streamChat({ message: 'hi' })

    expect(global.fetch.mock.calls[0][1].headers.Authorization).toBeUndefined()
  })

  // 限流 / 额度用完 / 人格越权 / 参数不合法时, 后端返回的是普通 JSON 而不是流.
  // 这里的 message 就是要显示给用户的那句话, 丢了的话界面上只会剩一个「请求失败」.
  it('surfaces the JSON error body when the request is rejected', async () => {
    global.fetch = mockFetch([], {
      ok: false,
      status: 429,
      json: { code: 429, message: '今日 AI 体验额度已用完, 明天再来。' },
    })

    await expect(streamChat({ message: 'hi' })).rejects.toThrow('今日 AI 体验额度已用完')
    await expect(streamChat({ message: 'hi' })).rejects.toMatchObject({ status: 429 })
  })

  it('still reports a failure when the rejection body is not JSON', async () => {
    global.fetch = vi.fn().mockResolvedValue({
      ok: false,
      status: 502,
      json: async () => {
        throw new Error('not json')
      },
      body: null,
    })

    await expect(streamChat({ message: 'hi' })).rejects.toThrow('502')
  })

  it('throws when the stream ends without a done event', async () => {
    global.fetch = mockFetch(['event:tool_call\ndata:{"round":1,"tool":"get_ranking"}\n\n'])

    await expect(streamChat({ message: 'hi' })).rejects.toThrow('连接中断')
  })

  it('throws the error event payload delivered inside a 200 stream', async () => {
    global.fetch = mockFetch([
      'event:error\ndata:{"message":"AI 服务执行出错, 请稍后重试"}\n\n',
    ])

    await expect(streamChat({ message: 'hi' })).rejects.toThrow('AI 服务执行出错')
  })

  it('exposes the event names the backend actually sends', () => {
    expect(SSE_EVENTS.TOOL_CALL).toBe('tool_call')
    expect(SSE_EVENTS.DONE).toBe('done')
  })

  /**
   * 下面两条钉的是 reader 的锁.
   *
   * `stream.locked` 是唯一能外部观察到的证据: getReader() 之后它就是 true,
   * releaseLock() 之后变回 false. 改前没有 finally, 于是流已经读完(或已经报错)
   * 而锁还挂在上面 —— 连接不回收, 用户每问一轮就攒一条谁也读不到的死连接.
   */
  it('正常读完后就放掉 reader 的锁', async () => {
    const encoder = new TextEncoder()
    const stream = new ReadableStream({
      start(controller) {
        controller.enqueue(encoder.encode('event:done\ndata:{"answer":"ok"}\n\n'))
        controller.close()
      },
    })
    global.fetch = vi.fn().mockResolvedValue({
      ok: true, status: 200, body: stream, json: async () => null,
    })

    await streamChat({ message: 'hi' })

    expect(stream.locked).toBe(false)
  })

  it('读到一半出错时也要放掉 reader 的锁', async () => {
    // 用户中途离开页面 / 点停止时, 真实 fetch 的响应体就是以这种方式报错的
    // (AbortError), 所以这条走的正是那条路径的形状
    const encoder = new TextEncoder()
    let inner
    const stream = new ReadableStream({
      start(controller) {
        inner = controller
        controller.enqueue(encoder.encode('event:tool_call\ndata:{"round":1}\n\n'))
      },
    })
    global.fetch = vi.fn().mockResolvedValue({
      ok: true, status: 200, body: stream, json: async () => null,
    })

    const promise = streamChat({ message: 'hi' }, () => {})
    await vi.waitFor(() => expect(stream.locked).toBe(true))
    inner.error(Object.assign(new Error('aborted'), { name: 'AbortError' }))

    await expect(promise).rejects.toMatchObject({ name: 'AbortError' })
    expect(stream.locked).toBe(false)
  })
})
