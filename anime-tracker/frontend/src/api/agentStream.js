/**
 * Agent 流式对话 —— 手动消费 SSE.
 *
 * 为什么不用浏览器自带的 EventSource:
 *   1. EventSource 只能发 GET, 而这里必须 POST 一个 JSON 请求体;
 *   2. EventSource 不能自定义请求头, 带不上 Authorization.
 * 两个限制都绕不过去, 所以只能用 fetch + ReadableStream 自己解析 SSE 帧.
 *
 * 帧格式 (Spring 的 SseEmitter 写出来的样子):
 *
 *     event:tool_call
 *     data:{"round":1,"tool":"get_ranking"}
 *     <空行>
 *
 * 要点: 一个 HTTP 分片里可能有半帧, 也可能有好几帧 ——
 * 分片边界和帧边界毫无关系. 所以必须留缓冲区, 只处理完整的帧,
 * 残缺的尾巴留到下一次. 这是手写 SSE 解析最容易写错的地方.
 */

/** 事件名 -> 后端推送的内容 */
export const SSE_EVENTS = {
  TOOL_CALL: 'tool_call',
  TOOL_RESULT: 'tool_result',
  DONE: 'done',
  ERROR: 'error',
}

/**
 * 把一段 SSE 文本切成完整的帧, 返回 { events, rest }.
 *
 * 单独抽成纯函数是为了能直接测: 分片错位的情况在真实网络里偶发,
 * 靠手动点页面基本测不出来.
 *
 * @param {string} buffer 累积的文本
 * @returns {{events: Array<{name: string, data: string}>, rest: string}}
 */
export function parseSseBuffer(buffer) {
  const events = []
  // 统一换行, 免得 CRLF 让帧尾匹配不上
  const text = buffer.replace(/\r\n/g, '\n')
  const parts = text.split('\n\n')

  // 最后一段可能是残缺的帧, 留在缓冲区里等下一个分片
  const rest = parts.pop()

  for (const part of parts) {
    if (!part.trim()) continue
    let name = 'message'
    const dataLines = []
    for (const line of part.split('\n')) {
      if (line.startsWith('event:')) {
        name = line.slice(6).trim()
      } else if (line.startsWith('data:')) {
        dataLines.push(line.slice(5).trim())
      }
      // 其余字段 (id: / retry: / 注释行) 这里用不到, 直接忽略
    }
    if (dataLines.length) {
      events.push({ name, data: dataLines.join('\n') })
    }
  }

  return { events, rest }
}

/** 解析 data 里的 JSON; 解析不出来就原样返回字符串, 至少不丢信息 */
function parseData(raw) {
  try {
    return JSON.parse(raw)
  } catch {
    return raw
  }
}

/** 从 localStorage 取 token, 与 axios 拦截器保持同一份来源 */
function authToken() {
  try {
    return JSON.parse(localStorage.getItem('anime_user') || 'null')?.token || null
  } catch {
    return null
  }
}

/**
 * 发一次流式提问.
 *
 * @param {object}   payload            { message, persona, conversationId, history }
 * @param {function} onEvent            (name, data) => void, 每收到一帧调一次
 * @param {AbortSignal} signal          用于「停止生成」
 * @returns {Promise<object>}           最终 done 事件的负载
 */
export async function streamChat(payload, onEvent, signal) {
  const token = authToken()
  const headers = { 'Content-Type': 'application/json' }
  if (token) {
    headers.Authorization = `Bearer ${token}`
  }

  const resp = await fetch('/api/agent/chat/stream', {
    method: 'POST',
    headers,
    body: JSON.stringify(payload),
    signal,
  })

  // 被拒绝时后端刻意返回的是普通 JSON 而不是 SSE 流
  // (限流、额度用完、人格越权、参数不合法都会走这条路),
  // 所以这里必须先按 JSON 解一次, 才能把「提问太频繁」这类原因如实显示给用户.
  if (!resp.ok) {
    let message = `请求失败 (${resp.status})`
    try {
      const body = await resp.json()
      if (body?.message) message = body.message
    } catch {
      // 不是 JSON 就保持默认提示
    }
    const err = new Error(message)
    err.status = resp.status
    throw err
  }

  if (!resp.body) {
    throw new Error('当前浏览器不支持流式响应')
  }

  const reader = resp.body.getReader()
  const decoder = new TextDecoder('utf-8')
  let buffer = ''
  let done = null
  let failure = null

  while (true) {
    const { value, done: streamEnd } = await reader.read()
    if (value) {
      buffer += decoder.decode(value, { stream: true })
    }

    const { events, rest } = parseSseBuffer(buffer)
    buffer = rest

    for (const evt of events) {
      const data = parseData(evt.data)
      if (evt.name === SSE_EVENTS.DONE) {
        done = data
      } else if (evt.name === SSE_EVENTS.ERROR) {
        // 后端把错误也走 200 的流推回来, 记下来在收流后抛, 避免上游还在推
        failure = new Error(data?.message || 'AI 服务执行出错')
      }
      onEvent?.(evt.name, data)
    }

    if (streamEnd) break
  }

  if (failure) throw failure
  if (!done) {
    // 流断了却没有 done: 多半是用户中途关页面或网络中断
    throw new Error('连接中断, 未收到完整回复')
  }
  return done
}
