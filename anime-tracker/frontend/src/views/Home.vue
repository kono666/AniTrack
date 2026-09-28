<template>
  <div class="home-page">
    <!-- Hero Carousel -->
    <HeroBanner v-if="!error" :items="heroItems" />

    <div class="page-container">
      <!-- Today's Schedule -->
      <div v-if="todayAnime.length > 0" class="today-section">
        <h2 class="section-heading" v-reveal>
          📅 今日放送
          <span class="today-date">{{ todayLabel }}</span>
        </h2>
        <div class="today-grid">
          <div
            v-for="item in todayAnime.slice(0, 8)"
            :key="item.id"
            class="today-card"
            @click="$router.push(`/anime/${item.id}`)"
          >
            <div class="today-cover">
              <img
                :src="item.images?.medium || item.images?.common || fallbackImg"
                :alt="item.nameCn"
                @error="e => e.target.src = fallbackImg"
              />
            </div>
            <div class="today-name">{{ item.nameCn || item.name }}</div>
          </div>
        </div>
      </div>

      <LoadingSpinner v-if="loading" />

      <!-- 加载失败. 排在内容前面, 而且把下面整块内容挡住 ——
           改前 Promise.all 一失败, 所有列表都是空的, 页面呈现出「这个站什么都没有」:
           空的热门、空的最近更新、只剩一个「全部」的分类栏. 用户不可能知道
           是后端挂了还是站里确实没数据 -->
      <EmptyState
        v-else-if="error"
        icon="⚠️"
        :message="error"
        action-label="重试"
        @action="loadHome"
      />

      <template v-else>
        <!-- Popular This Season -->
        <HorizontalScroll title="🔥 本季热门" link="/search?view=rank">
          <div
            v-for="(item, idx) in popularList"
            :key="item.id"
            class="hs-card"
            @click="$router.push(`/anime/${item.id}`)"
          >
            <div class="hs-card-rank" :class="'rank-' + (idx + 1)">{{ idx + 1 }}</div>
            <div class="hs-card-img-wrap">
              <img
                class="hs-card-img"
                :src="item.images?.large || item.images?.common || fallbackImg"
                :alt="item.nameCn"
                loading="lazy"
                @error="e => e.target.src = fallbackImg"
              />
              <div class="hs-card-score" v-if="item.rating?.score">⭐ {{ item.rating.score.toFixed(1) }}</div>
            </div>
            <div class="hs-card-title">{{ item.nameCn || item.name }}</div>
          </div>
        </HorizontalScroll>

        <!-- Recently Updated -->
        <HorizontalScroll title="🆕 最近更新" link="/search?view=date">
          <div
            v-for="item in recentList"
            :key="'r-' + item.id"
            class="hs-card"
            @click="$router.push(`/anime/${item.id}`)"
          >
            <div class="hs-card-img-wrap">
              <img
                class="hs-card-img"
                :src="item.images?.large || item.images?.common || fallbackImg"
                :alt="item.nameCn"
                loading="lazy"
                @error="e => e.target.src = fallbackImg"
              />
              <div class="hs-card-badge" v-if="item.totalEpisodes">{{ item.totalEpisodes }}集</div>
            </div>
            <div class="hs-card-title">{{ item.nameCn || item.name }}</div>
            <div class="hs-card-year" v-if="item.date">{{ item.date.substring(0, 4) }}</div>
          </div>
        </HorizontalScroll>

        <!-- Browse by Tag -->
        <div class="browse-section">
          <h2 class="section-heading" v-reveal>🏷️ 分类浏览</h2>
          <div class="tag-filter">
            <span class="tag-chip" :class="{ active: selectedTag === '' }" @click="selectTag('')">全部</span>
            <span
              class="tag-chip"
              v-for="tag in tags"
              :key="tag.name"
              :class="{ active: selectedTag === tag.name }"
              @click="selectTag(tag.name)"
            >
              {{ tag.name }}
              <span style="font-size:10px;opacity:.7;">({{ tag.count }})</span>
            </span>
          </div>
          <div v-if="tagResults.length > 0">
            <div class="anime-grid">
              <AnimeCard v-for="(item, idx) in pagedTagResults" :key="item.id" :anime="item" v-reveal="{ delay: idx * 40 }" />
            </div>
            <Pagination
              :current-page="tagPage"
              :total-pages="tagTotalPages"
              @change="tagPage = $event"
            />
          </div>
          <EmptyState v-else-if="selectedTag" icon="🏷️" message="该分类暂无数据" />
        </div>
      </template>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { getRanking, getCalendar, getTags, getByTag } from '../api'
import { loadErrorMessage } from '../utils/loadError'
import { useReveal } from '../composables/useReveal'
import HeroBanner from '../components/HeroBanner.vue'
import HorizontalScroll from '../components/HorizontalScroll.vue'
import AnimeCard from '../components/AnimeCard.vue'
import LoadingSpinner from '../components/LoadingSpinner.vue'
import EmptyState from '../components/EmptyState.vue'
import Pagination from '../components/Pagination.vue'

const { vReveal } = useReveal()

const $router = useRouter()
const loading = ref(true)
const heroItems = ref([])
const popularList = ref([])
const recentList = ref([])
const todayAnime = ref([])
const tags = ref([])
const selectedTag = ref('')
const error = ref('')
const tagResults = ref([])
const tagPage = ref(1)
const pageSize = 24

const fallbackImg = 'data:image/svg+xml,' + encodeURIComponent('<svg xmlns="http://www.w3.org/2000/svg" width="300" height="400" fill="#18181b"><rect width="300" height="400"/><text x="150" y="200" text-anchor="middle" fill="#3f3f46" font-size="16">No Cover</text></svg>')

const tagTotalPages = computed(() => Math.max(1, Math.ceil(tagResults.value.length / pageSize)))
const pagedTagResults = computed(() => {
  const start = (tagPage.value - 1) * pageSize
  return tagResults.value.slice(start, start + pageSize)
})

const todayLabel = computed(() => {
  const d = new Date()
  const weekdays = ['周日', '周一', '周二', '周三', '周四', '周五', '周六']
  return `${d.getMonth() + 1}月${d.getDate()}日 ${weekdays[d.getDay()]}`
})

async function selectTag(tag) {
  selectedTag.value = tag
  tagPage.value = 1
  if (tag) {
    try {
      const res = await getByTag(tag)
      tagResults.value = res.data.data || []
    } catch (e) { console.error(e) }
  } else {
    // "全部": 加载排行数据
    try {
      const res = await getRanking('date', 200)
      tagResults.value = res.data.data || []
    } catch (e) { console.error(e) }
  }
}

// 内存缓存 (5分钟TTL)
const cache = { data: null, time: 0 }
const CACHE_TTL = 5 * 60 * 1000

onMounted(loadHome)

// 单独取名(原来是直接写在 onMounted 里的匿名函数)是为了让错误态上的「重试」
// 有东西可调 —— 重试就是把这一次加载原样再跑一遍
async function loadHome() {
  error.value = ''
  // 命中缓存直接渲染
  if (cache.data && (Date.now() - cache.time) < CACHE_TTL) {
    const c = cache.data
    heroItems.value = c.hero; popularList.value = c.popular
    recentList.value = c.recent; todayAnime.value = c.today
    tags.value = c.tags; loading.value = false
    return
  }

  let coreLoaded = false
  try {
    // Phase1: 核心数据先加载 (快, 不阻塞页面)
    const [rankRes, dateRes, tagRes] = await Promise.all([
      getRanking('rank', 30),
      getRanking('date', 12),
      getTags(),
    ])
    const rankData = rankRes.data.data || []
    const dateData = dateRes.data.data || []
    heroItems.value = rankData.slice(0, 6)
    popularList.value = rankData
    recentList.value = dateData
    tags.value = tagRes.data.data || []
    loading.value = false  // 页面立即可见
    coreLoaded = true
  } catch (e) {
    // 改前只 console.error: 于是所有列表保持空, 页面看起来像「站里没数据」
    error.value = loadErrorMessage(e, '加载首页')
    loading.value = false
  }

  // Phase2: 日历后台加载 (慢, 不阻塞)
  try {
    const calRes = await getCalendar()
    const calData = calRes.data.data || []
    const weekdays = ['周日', '周一', '周二', '周三', '周四', '周五', '周六']
    const today = weekdays[new Date().getDay()]
    const todayEntry = calData.find(d => d.weekday?.cn === today)
    todayAnime.value = todayEntry?.items || []
  } catch (e) { /* 日历失败不影响主页 */ }

  // 写缓存 —— 只在核心数据真的加载成功时才写.
  //
  // 改前这里是无条件写的: 首页加载失败时, 这份「全是空列表」的结果会被当成有效
  // 数据缓存 5 分钟. 于是错误被缓存成了事实 —— 用户点重试(或者切走再回来)拿到的
  // 还是那份空缓存, 连一次新的请求都不会发出去.
  if (coreLoaded) {
    cache.data = { hero: heroItems.value, popular: popularList.value, recent: recentList.value, today: todayAnime.value, tags: tags.value }
    cache.time = Date.now()
  }
}
</script>

<style scoped>
.home-page { padding-bottom: 40px; }

/* ── Section Heading ── */
.section-heading {
  font-size: 20px; font-weight: 800; color: var(--text);
  margin-bottom: 16px; display: flex; align-items: center; gap: 10px;
}
.today-date { font-size: 13px; color: var(--primary); font-weight: 600; }

/* ── Today's Schedule ── */
.today-section { margin-bottom: 36px; }
.today-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(130px, 1fr));
  gap: 12px;
}
.today-card { cursor: pointer; transition: transform var(--transition); }
.today-card:hover { transform: translateY(-3px); }
.today-cover {
  aspect-ratio: 3/4; border-radius: var(--radius-sm);
  overflow: hidden; background: var(--bg-secondary);
  margin-bottom: 6px;
}
.today-cover img { width: 100%; height: 100%; object-fit: cover; transition: transform .4s; }
.today-card:hover .today-cover img { transform: scale(1.06); }
.today-name {
  font-size: 12px; font-weight: 600; color: var(--text);
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}

/* ── Horizontal Scroll Cards ── */
.hs-card {
  width: 160px; flex-shrink: 0;
  cursor: pointer; position: relative;
  scroll-snap-align: start;
  transition: transform var(--transition);
}
.hs-card:hover { transform: translateY(-4px); }
.hs-card-rank {
  position: absolute; top: -8px; left: -8px; z-index: 3;
  width: 32px; height: 32px; border-radius: 8px;
  display: flex; align-items: center; justify-content: center;
  font-size: 15px; font-weight: 900; color: #fff;
  background: var(--text-muted);
  box-shadow: 0 2px 8px rgba(0,0,0,.3);
}
.hs-card-rank.rank-1 { background: linear-gradient(135deg, #f59e0b, #d97706); }
.hs-card-rank.rank-2 { background: linear-gradient(135deg, #94a3b8, #64748b); }
.hs-card-rank.rank-3 { background: linear-gradient(135deg, #d97706, #92400e); }
.hs-card-img-wrap {
  aspect-ratio: 3/4; border-radius: var(--radius-sm);
  overflow: hidden; background: var(--bg-secondary);
  margin-bottom: 8px; position: relative;
}
.hs-card-img { width: 100%; height: 100%; object-fit: cover; transition: transform .4s; }
.hs-card:hover .hs-card-img { transform: scale(1.06); }
.hs-card-score {
  position: absolute; bottom: 6px; right: 6px;
  font-size: 10px; padding: 2px 7px;
  background: rgba(0,0,0,.8); color: var(--star);
  border-radius: 4px; font-weight: 700;
}
.hs-card-badge {
  position: absolute; bottom: 6px; left: 6px;
  font-size: 10px; padding: 2px 7px;
  background: rgba(0,0,0,.75); color: #fff;
  border-radius: 4px; font-weight: 600;
}
.hs-card-title {
  font-size: 13px; font-weight: 600; color: var(--text);
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.hs-card-year {
  font-size: 11px; color: var(--text-muted); margin-top: 2px;
}

/* ── Browse Section ── */
.browse-section { margin-top: 8px; }

/* ── Responsive ── */
@media (max-width: 768px) {
  .hs-card { width: 130px; }
  .today-grid { grid-template-columns: repeat(auto-fill, minmax(100px, 1fr)); gap: 10px; }
}
@media (max-width: 480px) {
  .hs-card { width: 110px; }
}
</style>
