<template>
  <div class="chat-msg" :class="'chat-msg--' + message.role">
    <div class="chat-avatar">
      <PhUser v-if="message.role === 'user'" :size="18" weight="bold" aria-hidden="true" />
      <PhRobot v-else :size="18" weight="bold" aria-hidden="true" />
    </div>

    <div class="chat-body">
      <!-- 工具调用时间线: Agent 的过程留痕. 折叠起来是「查了 3 次」, 展开能复盘每一步 -->
      <div v-if="steps.length" class="tool-timeline">
        <button class="tool-timeline-toggle" @click="expanded = !expanded">
          <PhWrench :size="13" weight="duotone" />
          <span>{{ summary }}</span>
          <PhCaretDown :size="11" weight="bold" class="tool-caret" :class="{ open: expanded }" />
        </button>

        <Transition name="tools">
          <ol v-if="expanded" class="tool-steps">
            <li
              v-for="(step, i) in steps"
              :key="i"
              class="tool-step"
              :class="{ 'tool-step--error': step.error, 'tool-step--running': step.running }"
            >
              <div class="tool-step-head">
                <component :is="toolMeta(step.tool).Icon" class="tool-step-icon" :size="13" weight="bold" aria-hidden="true" />
                <span class="tool-step-name">{{ toolMeta(step.tool).label }}</span>
                <code class="tool-step-raw">{{ step.tool }}</code>
                <span v-if="step.running" class="tool-step-state">执行中…</span>
                <span v-else-if="step.error" class="tool-step-state tool-step-state--error">失败</span>
                <span v-else-if="step.millis != null" class="tool-step-state">{{ step.millis }} ms</span>
              </div>

              <div v-if="hasArgs(step)" class="tool-step-args">
                <span v-for="(v, k) in step.args" :key="k" class="tool-arg">
                  {{ k }}={{ formatArg(v) }}
                </span>
              </div>

              <!-- 工具查到的番剧直接画成卡片: 光给一段 JSON, 用户还得自己再搜一次 -->
              <div v-if="step.cards && step.cards.length" class="tool-step-cards">
                <AnimeChip v-for="a in step.cards" :key="a.id" :anime="a" />
              </div>

              <pre v-if="step.preview" class="tool-step-preview">{{ shortPreview(step.preview) }}</pre>
            </li>
          </ol>
        </Transition>
      </div>

      <!-- 正文 -->
      <div class="chat-bubble" :class="{ 'chat-bubble--error': !!message.error }">
        <span v-if="message.streaming && !message.content" class="chat-typing">
          <i></i><i></i><i></i>
        </span>
        <template v-else>{{ message.content }}</template>
      </div>

      <div v-if="meta" class="chat-meta">{{ meta }}</div>
    </div>
  </div>
</template>

<script setup>
import { computed, ref } from 'vue'
import PhWrench from '@icons/PhWrench.vue.mjs'
import PhCaretDown from '@icons/PhCaretDown.vue.mjs'
import PhUser from '@icons/PhUser.vue.mjs'
import PhRobot from '@icons/PhRobot.vue.mjs'
import AnimeChip from './AnimeChip.vue'
import { toolMeta } from '../utils/agentTools'

const props = defineProps({
  message: { type: Object, required: true },
})

// 默认展开: 用户第一次看到「AI 真的去查了数据」比看到一段漂亮话更有价值,
// 但每轮都自动展开会淹没正文, 所以只在还没出结论时展开
const expanded = ref(true)

const steps = computed(() => props.message.steps || [])

const summary = computed(() => {
  const n = steps.value.length
  const failed = steps.value.filter(s => s.error).length
  const base = `调用了 ${n} 个工具`
  return failed ? `${base} · ${failed} 个失败` : base
})

const meta = computed(() => {
  const m = props.message
  const bits = []
  if (m.rounds) bits.push(`${m.rounds} 轮`)
  if (m.inputTokens || m.outputTokens) {
    bits.push(`token ${m.inputTokens || 0}/${m.outputTokens || 0}`)
  }
  if (m.truncated) bits.push('已达轮数上限')
  return bits.join(' · ')
})

function hasArgs(step) {
  return step.args && Object.keys(step.args).length > 0
}

function formatArg(v) {
  if (v === null || v === undefined) return '—'
  const s = typeof v === 'object' ? JSON.stringify(v) : String(v)
  return s.length > 40 ? s.slice(0, 40) + '…' : s
}

function shortPreview(text) {
  const t = String(text)
  return t.length > 400 ? t.slice(0, 400) + '…' : t
}
</script>
