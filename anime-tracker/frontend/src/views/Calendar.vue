<template>
  <div class="page-container">
    <div class="page-header">
      <h1>整周放送</h1>
    </div>

    <!-- 加载失败要把整页挡掉, 而不是渲染七个空格子 —— 后者看上去像"这一周什么都没播",
         而真正发生的是"没取到". 与首页那条 error 分支同一个判断. -->
    <LoadingSpinner v-if="loading" />
    <EmptyState
      v-else-if="error"
      type="error"
      :message="error"
      action-label="重试"
      @action="load"
    />

    <template v-else>
      <section v-for="wd in BGM_WEEKDAYS" :key="wd.id" class="cal-day">
        <h2 class="cal-day-hd">
          {{ wd.cn }}
          <!-- 「今天」是**文字**不是靠颜色: 色差在深色/浅色两套主题下各自不同, 而且
               对读屏用户完全不存在. 这一页有七格, 用户最想先找到的就是这一格. -->
          <span v-if="wd.id === todayId" class="cal-today">今天</span>
        </h2>
        <p v-if="!itemsOf(wd.id).length" class="cal-empty">暂无排片</p>
        <div v-else class="cal-grid">
          <div
            v-for="item in itemsOf(wd.id)"
            :key="item.id"
            class="cal-card"
            role="button"
            tabindex="0"
            @click="open(item.id)"
            @keydown.enter.prevent="open(item.id)"
            @keydown.space.prevent="open(item.id)"
          >
            <div class="cal-cover">
              <img
                :src="item.images?.medium || item.images?.common || fallbackImg"
                :alt="item.nameCn || item.name"
                loading="lazy"
                @error="e => e.target.src = fallbackImg"
              />
            </div>
            <div class="cal-name">{{ item.nameCn || item.name }}</div>
          </div>
        </div>
      </section>
    </template>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { getCalendar } from '../api'
import { loadErrorMessage } from '../utils/loadError'
import { COVER_FALLBACK as fallbackImg } from '../utils/fallbackImg'
import { BGM_WEEKDAYS, bgmWeekdayId } from '../utils/bgmWeekday'
import LoadingSpinner from '../components/LoadingSpinner.vue'
import EmptyState from '../components/EmptyState.vue'

const router = useRouter()
const days = ref([])
const loading = ref(true)
const error = ref('')

/** 今天那一格的标号. 与首页「今日放送」读同一个函数 —— 它错过一次, 不抄第二份 */
const todayId = bgmWeekdayId()

/**
 * 七个格子**恒定渲染**, 顺序也恒定(周一→周日), 数据里没有的那天就是「暂无排片」.
 *
 * 为什么不按接口返回的那几天遍历: 接口返回的是一个数组, 顺序与完整性都是它的实现细节
 * —— 哪天缺了, 页面上就会少一格, 而"少了周三"与"周三没排片"在视觉上是两件事,
 * 用户看不出是哪个. 按固定的七格去查, 缺的那格会明说自己空着.
 *
 * 返回的数组是**新建的**而不是 `?? EMPTY` 那种共享常量: `v-for` 拿到共享数组没问题,
 * 但共享的可变数组一旦被谁 push 一下, 全站七个格子一起变.
 */
function itemsOf(weekdayId) {
  const found = days.value.find(d => String(d.weekday?.id) === String(weekdayId))
  return found?.items || []
}

function open(id) {
  router.push(`/anime/${id}`)
}

async function load() {
  error.value = ''
  loading.value = true
  try {
    const res = await getCalendar()
    days.value = res.data.data || []
  } catch (e) {
    error.value = loadErrorMessage(e, '加载放送表')
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<style scoped>
/* ── 为什么这里的卡片样式是"抄"首页的 ──
   首页 `.today-grid` / `.today-card` / `.today-cover` / `.today-name` 那一套与这里
   几乎一样, 而这一页**刻意**又写了一遍, 不进 assets/css/ 也不抽组件:
     · 进全局 CSS 不行 —— 那一层是跨页共享的, 而这一页只有一个调用方;
     · 抽公共组件不行 —— 要同时动 Home 的模板、样式, 以及 Home.today.test.js 里
       那批 `find('.today-card')`. 那是另一件事的改动范围, 不该混进来.
   代价是约 15 行重复, 而两份的**取值**仍然只有一处(见 utils/bgmWeekday.js)。
   真要抽的时候, 判据是"第三处调用方出现"。
   与首页的唯一差异是列宽: 首页那是"某一天的排片", 这里是"七天并排", 窄屏下需要更小的下限。 */
.cal-day { margin-bottom: 28px; }
.cal-day-hd {
  display: flex; align-items: center; gap: 10px;
  font-size: 15px; font-weight: 800; color: var(--text);
  margin-bottom: 10px;
}
.cal-today {
  font-size: 11px; font-weight: 700; letter-spacing: .04em;
  color: var(--primary); background: var(--primary-soft);
  border-radius: var(--radius-sm); padding: 2px 7px;
}
.cal-empty { font-size: 13px; color: var(--text-muted); }

.cal-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(130px, 1fr));
  gap: 12px;
}
.cal-card { cursor: pointer; transition: transform var(--transition); }
.cal-card:hover { transform: translateY(-3px); }
.cal-cover {
  aspect-ratio: 3/4; border-radius: var(--radius-sm);
  overflow: hidden; background: var(--bg-secondary);
  margin-bottom: 6px;
}
.cal-cover img { width: 100%; height: 100%; object-fit: cover; transition: transform .4s; }
.cal-card:hover .cal-cover img { transform: scale(1.06); }
.cal-name {
  font-size: 12px; font-weight: 600; color: var(--text);
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}

@media (max-width: 768px) {
  .cal-grid { grid-template-columns: repeat(auto-fill, minmax(100px, 1fr)); gap: 10px; }
}
</style>
