<template>
  <div class="assistant-page">
    <!-- ── 会话侧栏 (登录后才有: 访客的对话不落库, 也就没有列表可列) ── -->
    <aside v-if="loggedIn" class="chat-sidebar" :class="{ open: sidebarOpen }">
      <button class="new-chat-btn" @click="newChat">
        <PhPlus :size="15" weight="bold" /> 新对话
      </button>

      <div class="sidebar-label">历史会话</div>
      <div v-if="!conversations.length" class="sidebar-empty">还没有保存的会话</div>
      <ul class="conv-list">
        <li
          v-for="c in conversations"
          :key="c.id"
          class="conv-item"
          :class="{ active: c.id === conversationId }"
          @click="openConversation(c.id)"
        >
          <div class="conv-main">
            <div class="conv-title">{{ c.title || '未命名会话' }}</div>
            <div class="conv-sub">{{ c.messageCount }} 条 · {{ formatTime(c.updatedAt) }}</div>
          </div>
          <button class="conv-del" title="删除" @click.stop="removeConversation(c.id)">
            <PhTrash :size="14" weight="duotone" />
          </button>
        </li>
      </ul>
    </aside>

    <!-- ── 主区 ── -->
    <section class="chat-main">
      <header class="chat-header">
        <div class="chat-header-left">
          <button v-if="loggedIn" class="sidebar-toggle" @click="sidebarOpen = !sidebarOpen">
            <PhList :size="16" weight="bold" />
          </button>
          <div>
            <h1 class="chat-title">AI 助手</h1>
            <div class="chat-subtitle">
              <span class="model-chip">{{ info?.provider }} · {{ info?.model }}</span>
              <span v-if="quotaText" class="quota-chip" :class="{ low: quotaLow }">{{ quotaText }}</span>
            </div>
          </div>
        </div>

        <!-- 人格切换: 管理员多一个「运营分析」, 用的是另一套工具和提示词 -->
        <div class="persona-switch">
          <button
            v-for="p in personas"
            :key="p.id"
            class="persona-btn"
            :class="{ active: persona === p.id, disabled: !p.available }"
            :disabled="!p.available"
            :title="p.available ? '' : '仅管理员可用'"
            @click="switchPersona(p.id)"
          >{{ p.name }}</button>
        </div>
      </header>

      <div ref="scrollEl" class="chat-scroll">
        <!-- 空白引导 -->
        <div v-if="!messages.length" class="chat-intro">
          <div class="intro-icon">🤖</div>
          <h2>{{ persona === 'admin-analyst' ? '问问平台的运营情况' : '想找什么番？' }}</h2>
          <p class="intro-desc">
            这个助手会真的去查数据库 —— 每轮调用了哪个工具、拿到什么结果, 都会摊开给你看。
          </p>
          <div class="suggestions">
            <button
              v-for="s in suggestions"
              :key="s"
              class="suggestion"
              @click="send(s)"
            >{{ s }}</button>
          </div>
          <p v-if="!loggedIn" class="intro-hint">
            当前是访客模式, 对话不会被保存。<router-link to="/login">登录</router-link>后可以留存会话记录。
          </p>
        </div>

        <ChatMessage v-for="(m, i) in messages" :key="i" :message="m" />
      </div>

      <footer class="chat-composer">
        <div class="composer-box" :class="{ disabled: !canSend }">
          <textarea
            v-model="input"
            class="composer-input"
            rows="1"
            :placeholder="placeholder"
            :disabled="!canSend"
            @keydown.enter.exact.prevent="send()"
            @input="autoGrow"
            ref="inputEl"
          ></textarea>

          <button v-if="busy" class="composer-btn composer-stop" title="停止" @click="stop">
            <PhStop :size="16" weight="fill" />
          </button>
          <button
            v-else
            class="composer-btn"
            :disabled="!input.trim() || !canSend"
            title="发送"
            @click="send()"
          >
            <PhPaperPlaneTilt :size="16" weight="fill" />
          </button>
        </div>
        <div class="composer-foot">
          <span>Enter 发送 · Shift+Enter 换行</span>
          <span v-if="!canSend" class="composer-blocked">{{ blockedReason }}</span>
        </div>
      </footer>
    </section>
  </div>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import {
  PhPlus, PhTrash, PhList, PhPaperPlaneTilt, PhStop,
} from '@phosphor-icons/vue'
import ChatMessage from '../components/ChatMessage.vue'
import { useUserStore } from '../stores/user'
import {
  getAgentInfo, getConversations, getConversation, deleteConversation,
} from '../api'
import { streamChat } from '../api/agentStream'

const userStore = useUserStore()
const loggedIn = computed(() => userStore.loggedIn)

const info = ref(null)
const messages = reactive([])
const conversations = ref([])
const conversationId = ref(null)
const persona = ref('user-assistant')
const input = ref('')
const busy = ref(false)
const sidebarOpen = ref(false)
const scrollEl = ref(null)
const inputEl = ref(null)
const remaining = ref(null)
let controller = null

const personas = computed(() => info.value?.personas || [])
const dailyLimit = computed(() => info.value?.dailyLimit ?? 0)

const quotaText = computed(() => {
  if (dailyLimit.value <= 0 || remaining.value === null) return ''
  return `今日剩余 ${remaining.value} 次`
})
const quotaLow = computed(() => dailyLimit.value > 0 && remaining.value <= dailyLimit.value * 0.1)

// 额度用完时不该还让人打字 —— 后端会给 429, 但让用户白打一段字再被拒是很差的体验
const exhausted = computed(() => dailyLimit.value > 0 && remaining.value === 0)
const canSend = computed(() => !exhausted.value)
const blockedReason = computed(() => (exhausted.value ? '今日体验额度已用完, 明天再来' : ''))

const placeholder = computed(() => {
  if (exhausted.value) return blockedReason.value
  return persona.value === 'admin-analyst' ? '问问平台数据…' : '想找什么番？说个类型、年份或者直接报名字…'
})

const SUGGESTIONS_USER = [
  '最近有什么高分番推荐？',
  '给我推荐几部治愈系动画',
  '2024 年评分最高的番是哪部？',
]
const SUGGESTIONS_ADMIN = [
  '平台现在的整体情况怎么样？',
  '哪些番被追得最多？',
  '生成一份运营周报',
]
const suggestions = computed(() =>
  persona.value === 'admin-analyst' ? SUGGESTIONS_ADMIN : SUGGESTIONS_USER
)

onMounted(async () => {
  await loadInfo()
  if (loggedIn.value) {
    await loadConversations()
  }
})

/**
 * 离开页面时中断还在跑的流.
 *
 * 之前没有这个钩子, 后果是: 生成到一半切到别的路由, 那条 SSE 连接还开着,
 * 后端还在推, 而每推一帧回调都会去改一个已经不在页面上的组件的状态. 用户看到的是
 * 「切走了还在偷偷跑」, 服务端那边则是一份没人要的计算 —— AI 调用是要花钱的,
 * 让它跑完一整轮工具调用只为了把结果丢进虚空, 没有道理.
 *
 * 中断后 streamChat 会以 AbortError 拒绝, send() 的 catch 已经把这种情况认成
 * 「已停止生成」, 所以不需要额外处理.
 */
onBeforeUnmount(() => {
  controller?.abort()
})

async function loadInfo() {
  try {
    const res = await getAgentInfo()
    if (res.data.code === 200) {
      info.value = res.data.data
      remaining.value = res.data.data.dailyRemaining
      // 服务端说这个身份用不了当前人格 (比如退出登录后还停在运营分析), 退回去
      const current = (res.data.data.personas || []).find(p => p.id === persona.value)
      if (!current || !current.available) {
        persona.value = 'user-assistant'
      }
    }
  } catch (e) {
    console.error('获取助手信息失败', e)
  }
}

async function loadConversations() {
  try {
    const res = await getConversations()
    if (res.data.code === 200) conversations.value = res.data.data
  } catch {
    // 列表拉不到不影响当前对话, 静默即可
  }
}

/** 把历史消息交给后端. 只在还没有会话 id 时用得上 —— 有了 id 就以服务端记录为准 */
function localHistory() {
  return messages
    .filter(m => m.content && !m.error)
    .map(m => ({ role: m.role, content: m.content }))
}

async function send(preset) {
  const text = (preset ?? input.value).trim()
  if (!text || busy.value || !canSend.value) return

  input.value = ''
  resetInputHeight()

  messages.push({ role: 'user', content: text })

  const assistant = reactive({
    role: 'assistant',
    content: '',
    steps: [],
    streaming: true,
  })
  messages.push(assistant)
  scrollToBottom()

  busy.value = true
  controller = new AbortController()

  const payload = { message: text, persona: persona.value }
  if (conversationId.value) {
    payload.conversationId = conversationId.value
  } else {
    // 新会话的第一轮: 后端没有记录, 只能靠本地历史接上上下文
    const history = localHistory().slice(0, -1)
    if (history.length) payload.history = history
  }

  try {
    const done = await streamChat(payload, (name, data) => {
      if (name === 'tool_call') {
        assistant.steps.push({
          round: data.round, tool: data.tool, args: data.args, running: true,
        })
      } else if (name === 'tool_result') {
        // 按「轮次 + 工具名」找回刚才那一步. 不做成按顺序配对,
        // 是因为一轮里模型可能同时调好几个工具
        const step = assistant.steps.find(
          s => s.running && s.round === data.round && s.tool === data.tool
        )
        if (step) {
          step.running = false
          step.error = data.error
          step.preview = data.preview
          // 卡片由后端从工具结果里挑好 (见 ToolCards), 前端不解析 preview:
          // 那是一段被截断过的 JSON, 解析它纯属给自己找失败的可能
          step.cards = data.cards || []
        }
      }
      scrollToBottom()
    }, controller.signal)

    assistant.content = done.answer
    assistant.rounds = done.rounds
    assistant.inputTokens = done.inputTokens
    assistant.outputTokens = done.outputTokens
    assistant.truncated = done.truncated
    if (typeof done.dailyRemaining === 'number') remaining.value = done.dailyRemaining

    if (done.conversationId) {
      conversationId.value = done.conversationId
      loadConversations()
    }
  } catch (e) {
    if (e.name === 'AbortError') {
      assistant.content = assistant.content || '（已停止生成）'
    } else {
      assistant.error = true
      assistant.content = e.message || '请求失败'
      // 出错后重新问一次服务端还剩多少额度: 可能是额度刚好用完,
      // 也可能是限流 —— 以服务端的数字为准, 不靠猜错误文案
      await loadInfo()
    }
  } finally {
    // 收流后把所有还挂着的步骤标成结束, 免得界面上一直转圈
    assistant.steps.forEach(s => { s.running = false })
    assistant.streaming = false
    busy.value = false
    controller = null
    scrollToBottom()
  }
}

function stop() {
  controller?.abort()
}

function newChat() {
  messages.splice(0, messages.length)
  conversationId.value = null
  sidebarOpen.value = false
}

function switchPersona(id) {
  if (id === persona.value) return
  persona.value = id
  // 换人格等于换一套提示词和工具, 接着上一个会话聊会让模型看到前后矛盾的历史
  newChat()
}

async function openConversation(id) {
  if (id === conversationId.value) return
  try {
    const res = await getConversation(id)
    if (res.data.code !== 200) return
    const detail = res.data.data

    messages.splice(0, messages.length)
    for (const m of detail.messages) {
      messages.push({ role: m.role, content: m.content, steps: [] })
    }
    conversationId.value = id
    if (detail.persona) persona.value = detail.persona
    sidebarOpen.value = false
    scrollToBottom()
  } catch (e) {
    console.error('打开会话失败', e)
  }
}

async function removeConversation(id) {
  try {
    await deleteConversation(id)
    if (id === conversationId.value) newChat()
    await loadConversations()
  } catch (e) {
    console.error('删除会话失败', e)
  }
}

/** 输入框跟着内容长高, 但不超过 6 行 */
function autoGrow() {
  const el = inputEl.value
  if (!el) return
  el.style.height = 'auto'
  el.style.height = Math.min(el.scrollHeight, 160) + 'px'
}

function resetInputHeight() {
  if (inputEl.value) inputEl.value.style.height = 'auto'
}

function scrollToBottom() {
  nextTick(() => {
    if (scrollEl.value) scrollEl.value.scrollTop = scrollEl.value.scrollHeight
  })
}

function formatTime(iso) {
  if (!iso) return ''
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return ''
  const now = new Date()
  const sameDay = d.toDateString() === now.toDateString()
  const hh = String(d.getHours()).padStart(2, '0')
  const mm = String(d.getMinutes()).padStart(2, '0')
  return sameDay ? `${hh}:${mm}` : `${d.getMonth() + 1}/${d.getDate()}`
}
</script>
