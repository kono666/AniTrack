<template>
  <div v-if="subject" class="detail-page">
    <!-- ====== HERO ====== -->
    <section class="d-hero">
      <div class="d-hero-bg">
        <img v-if="heroBg" :src="heroBg" class="d-hero-bg-img" />
        <div class="d-hero-mesh"></div>
      </div>
      <div class="d-hero-content page-container">
        <div class="d-hero-left">
          <div class="d-cover-wrap">
            <img class="d-cover" :src="coverImg" :alt="subject.nameCn" @error="onCoverError" />
            <div class="d-cover-score" v-if="subject.rating?.score"><PhStar :size="12" weight="fill" /> {{ subject.rating.score.toFixed(1) }}</div>
            <div class="d-cover-rank" v-if="subject.rating?.rank">#{{ subject.rating.rank }}</div>
          </div>
          <div class="d-hero-actions">
            <button v-if="userStore.loggedIn" class="d-btn-track" :class="{ tracking: trackForm.id }" @click="trackForm.id ? removeTrack() : quickTrack()">
              {{ trackForm.id ? '✓ 已追番' : '+ 追番' }}
            </button>
            <button v-else class="d-btn-track" @click="$router.push('/login')">+ 追番</button>
          </div>
        </div>
        <div class="d-hero-right">
          <h1 class="d-title">{{ subject.nameCn || subject.name }}</h1>
          <p v-if="subject.nameCn && subject.name !== subject.nameCn" class="d-subtitle">{{ subject.name }}</p>
          <!-- 缺哪一项就整项不渲染, 而不是摆一个 "#-" / "?" 出来.
               两件事在这里被混成了一件: 「这个字段还没采到」和「这部番的这些值是零」.
               访客读到的是「这站坏了」, 而不是「这站还年轻」; 而站内 2.9 万部里绝大多数
               都是没名次、没总集数、没人追的 —— 出现在最显眼的那一排的, 恰恰是最常见的
               那一种页面.
               注意 类型 那一格: 改前写的是 platform || 'TV', 缺值时**猜一个 TV**.
               一部剧场版没有 platform 就会被标成"类型 TV". 缺数据可以补, 错数据会被当真. -->
          <div class="d-stats" v-if="hasStats">
            <div class="d-stat" v-if="subject.rating?.score"><span class="ds-val">{{ subject.rating.score.toFixed(1) }}</span><span class="ds-lbl">评分</span></div>
            <div class="d-stat" v-if="subject.rating?.rank"><span class="ds-val">#{{ subject.rating.rank }}</span><span class="ds-lbl">排名</span></div>
            <div class="d-stat" v-if="subject.totalEpisodes"><span class="ds-val">{{ subject.totalEpisodes }}</span><span class="ds-lbl">总集数</span></div>
            <div class="d-stat" v-if="subject.date"><span class="ds-val">{{ subject.date.substring(0,4) }}</span><span class="ds-lbl">年份</span></div>
            <div class="d-stat" v-if="subject.platform"><span class="ds-val">{{ subject.platform }}</span><span class="ds-lbl">类型</span></div>
          </div>
          <div class="d-tags" v-if="subject.tags?.length">
            <span v-for="tag in subject.tags.slice(0, 6)" :key="tag.name" class="d-tag">{{ tag.name }}</span>
          </div>
          <p class="d-summary">{{ subject.summary || '暂无简介' }}</p>
          <!-- 三项全零 = 还没有人碰过它. 一行「0人想看 0人在看 0人看过」不是"人气为零",
               是这个功能还没有数据 —— 那就别摆出来 -->
          <div class="d-heat" v-if="heatTotal > 0">
            <span>{{ heat.wantToWatch }}人想看</span>
            <span>{{ heat.watching }}人在看</span>
            <span>{{ heat.watched }}人看过</span>
          </div>
        </div>
      </div>
    </section>

    <!-- ====== CONTENT ====== -->
    <div class="page-container">
      <!-- Tracking detail (logged in) -->
      <div v-if="userStore.loggedIn && trackForm.id" class="d-track-bar">
        <div class="track-status-btns">
          <button v-for="s in statusOptions" :key="s.value"
            class="track-status-btn" :class="{ active: trackForm.status === s.value }"
            @click="trackForm.status = s.value">{{ s.label }}</button>
        </div>
        <!-- min="0" 与 :max 只是浏览器给的护栏(拖动步进箭头时用), 提交时不算数 ——
             手打一个 999 照样能提交, 所以 saveTrack 里还有一道 clamp. 两道都要:
             只有前者的话手打能绕过去, 只有后者的话用户得先提交才知道自己填错了 -->
        <div class="track-input-row">
          <label>进度</label><input type="number" v-model.number="trackForm.progress" min="0" :max="maxProgress" />
          <span>/ {{ subject.totalEpisodes || '?' }}</span>
          <label style="margin-left:16px;">评分</label><input type="number" v-model.number="trackForm.score" min="1" max="10" />
        </div>
        <button class="d-btn-save" @click="saveTrack">保存</button>
      </div>

      <!-- Episode Grid -->
      <section class="d-section">
        <SectionHeader title="剧集列表">
          <template #extra>
            <span v-if="userStore.loggedIn && watchedEpisodes.length > 0">已看 {{ watchedEpisodes.length }} / {{ episodes.length }}</span>
          </template>
        </SectionHeader>
        <!-- 判据仍然是 episodes.length(全量), 不是切片后的那个 —— 否则"这一页空了"
             这种错觉会出现(篇幅长的番翻到最后一页时不该说"暂无剧集数据") -->
        <template v-if="episodes.length > 0">
          <div ref="epSectionTop" class="ep-tile-grid">
            <button
              v-for="ep in visibleEpisodes" :key="ep.id"
              class="ep-tile"
              :class="{ watched: watchedEpisodes.includes(ep.sort) }"
              @click="toggleEp(ep.sort)"
            >
              <span class="ep-tile-num">{{ ep.sort }}</span>
              <span class="ep-tile-name">{{ ep.nameCn || ep.name || '第'+ep.sort+'集' }}</span>
              <span v-if="watchedEpisodes.includes(ep.sort)" class="ep-check">✓</span>
            </button>
          </div>
          <Pagination
            v-if="epPaginated"
            :current-page="epPage"
            :total-pages="epTotalPages"
            @change="changeEpPage"
          />
        </template>
        <EmptyState v-else type="episode" message="暂无剧集数据" />
      </section>

      <!-- Related -->
      <section v-if="relatedAnime.length > 0" class="d-section">
        <SectionHeader title="相关推荐" />
        <div class="related-scroll">
          <div
            v-for="item in relatedAnime"
            :key="item.id"
            class="related-card"
            role="button"
            tabindex="0"
            @click="$router.push(`/anime/${item.id}`)"
            @keydown.enter.prevent="$router.push(`/anime/${item.id}`)"
            @keydown.space.prevent="$router.push(`/anime/${item.id}`)"
          >
            <div class="rc-cover">
              <img :src="item.images?.common || item.images?.medium || fallbackImg" :alt="item.nameCn" @error="e=>e.target.src=fallbackImg" />
              <div class="rc-score" v-if="item.rating?.score"><PhStar :size="11" weight="fill" />{{ item.rating.score.toFixed(1) }}</div>
            </div>
            <div class="rc-title">{{ item.nameCn || item.name }}</div>
            <div class="rc-year" v-if="item.date">{{ item.date.substring(0,4) }}</div>
          </div>
        </div>
      </section>

      <!-- Reviews -->
      <section class="d-section">
        <SectionHeader :title="`评论 · ${ratingStats.count}`">
          <template #extra>均分 <PhStar :size="11" weight="fill" />{{ ratingStats.average }}</template>
        </SectionHeader>

        <!-- My Review -->
        <div v-if="userStore.loggedIn" class="my-review">
          <div class="mr-stars">
            <button v-for="n in 10" :key="n" class="mr-star" :class="{ on: n <= myReview.rating }" @click="myReview.rating = n">★</button>
          </div>
          <textarea v-model="myReview.content" placeholder="写评论..." rows="2" class="mr-input"></textarea>
          <button class="d-btn-save" @click="submitReview" style="margin-top:8px;">{{ myReview.id ? '更新' : '提交' }}</button>
          <button v-if="myReview.id" class="d-btn-ghost" @click="deleteMyReview" style="margin-left:8px;">删除</button>
        </div>

        <!-- Rating bars -->
        <div v-if="reviews.length > 0" class="rate-bars">
          <div v-for="(cnt,i) in ratingStats.distribution" :key="i" class="rate-bar-row">
            <span class="rbr-label">{{ i+1 }}</span>
            <div class="rbr-track"><div class="rbr-fill" :style="{width: barPct(cnt, ratingStats.distribution)+'%'}"></div></div>
            <span class="rbr-cnt">{{ cnt }}</span>
          </div>
        </div>

        <!-- 排序开关. 放在列表正上方、右对齐 —— 视觉上就是这一块的右上角。
             不塞进 SectionHeader 的 #extra: 那个插槽外面裹着 <span class="sec-extra">,
             往里面放一排 <button> 是行内元素套块级内容, 而且那个插槽现在装的是
             「均分 ★8.6」, 两者挤在一起会互相抢读的顺位。

             少于一页时不渲染: 一条评论排序没有意义, 摆出来只是一行永远点不出差别的按钮. -->
        <div v-if="reviews.length > 1" class="rv-sort">
          <button class="rv-sort-btn" :class="{ active: reviewSort === REVIEW_SORT_CREATED }"
            @click="setReviewSort(REVIEW_SORT_CREATED)">最新</button>
          <button class="rv-sort-btn" :class="{ active: reviewSort === REVIEW_SORT_HOT }"
            @click="setReviewSort(REVIEW_SORT_HOT)">最热</button>
        </div>

        <!-- Review list -->
        <div v-if="reviews.length > 0" class="review-list">
          <div v-for="r in reviews" :key="r.id" class="rv-item">
            <div class="rv-avatar">{{ (r.username||'?')[0] }}</div>
            <div class="rv-body">
              <div class="rv-top">
                <span class="rv-username">{{ r.username }}</span>
                <span class="rv-stars">{{ '★'.repeat(r.rating) }}</span>
                <span class="rv-time">{{ fmt(r.createdAt) }}</span>
              </div>
              <div class="rv-text">{{ r.content || '（无文字）' }}</div>
              <div class="rv-actions">
                <!-- 未登录也照渲染, 点了去登录页(与这一页「+ 追番」同一套做法) ——
                     藏起来的话, 访客根本不知道这站有点赞这回事 -->
                <button class="rv-act" :class="{ on: r.likedByMe }" :disabled="Boolean(likeBusy[r.id])"
                  :aria-pressed="r.likedByMe ? 'true' : 'false'" @click="toggleLike(r)">
                  <PhHeart :size="14" :weight="r.likedByMe ? 'fill' : 'regular'" />
                  <span>{{ r.likeCount || 0 }}</span>
                </button>
                <!-- 一个赞都没有时不摆「谁赞了」: 点开来是空的, 那是一句"这里有东西"
                     的谎话. 计数偏了(行数比计数少)时会有名字为空的情况, 那种空态由
                     下面那块自己兜 -->
                <button v-if="(r.likeCount || 0) > 0" class="rv-act rv-act-quiet"
                  @click="toggleLikers(r)">
                  {{ likerBox[r.id]?.open ? '收起' : '谁赞了' }}
                </button>
              </div>
              <div v-if="likerBox[r.id]?.open" class="rv-likers">
                <span v-if="likerBox[r.id].loading" class="rv-likers-hint">加载中…</span>
                <span v-else-if="!likerBox[r.id].names.length" class="rv-likers-hint">暂无</span>
                <template v-else>
                  <span v-for="u in likerBox[r.id].names" :key="u.userId" class="rv-liker">{{ u.username }}</span>
                  <!-- 名单在服务端封顶(50), 超出时把真实总数说出来 ——
                       否则"12 人赞过"下面只列 5 个名字, 看起来像漏了 -->
                  <span v-if="likerBox[r.id].total > likerBox[r.id].names.length" class="rv-likers-hint">
                    等共 {{ likerBox[r.id].total }} 人
                  </span>
                </template>
              </div>
            </div>
          </div>
        </div>
        <div v-else-if="!userStore.loggedIn" class="no-rev">
          暂无评论，<router-link to="/login">登录</router-link>后参与讨论
        </div>
      </section>
    </div>
  </div>

  <div v-else class="page-container">
    <LoadingSpinner v-if="loading" />
    <!-- 加载失败与「编号不存在」是两件事, 改前它们共用下面那一句:
         断网或后端 500 时, 用户看到的是「番剧不存在或已下架」——
         一句关于**这部番**的结论, 而事实是**这次请求**没成功.
         对用户来说差别很大: 前者会让他以为链接失效了, 后者他重试一下就好 -->
    <EmptyState
      v-else-if="error"
      type="error"
      :message="error"
      action-label="重试"
      @action="load"
    />
    <div v-else class="not-found">番剧不存在或已下架</div>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import PhStar from '@icons/PhStar.vue.mjs'
import PhHeart from '@icons/PhHeart.vue.mjs'
import { useRoute, useRouter } from 'vue-router'
import { useUserStore } from '../stores/user'
import {
  getAnimeDetail, getEpisodes, getRatingStats, getSubjectReviews,
  getMyReview, saveReview, deleteMyReview as delReviewApi,
  getTrackingStatus, saveTracking, deleteTracking,
  getWatchedEpisodes, toggleEpisode, getAnimeHeat, getFiltered,
  likeReview, unlikeReview, getReviewLikers,
  REVIEW_SORT_CREATED, REVIEW_SORT_HOT
} from '../api'
import { loadErrorMessage } from '../utils/loadError'
import { COVER_FALLBACK_CARD as fallbackImg } from '../utils/fallbackImg'
import { useToast } from '../composables/useToast'
import SectionHeader from '../components/SectionHeader.vue'
import LoadingSpinner from '../components/LoadingSpinner.vue'
import EmptyState from '../components/EmptyState.vue'
import Pagination from '../components/Pagination.vue'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const { show: toast } = useToast()
const sid = Number(route.params.id)

const subject = ref(null)
const episodes = ref([])

/* ── 剧集分页 ──
   长篇番(海贼王 1000+)改前是一次把**全部**剧集铺成 DOM: 1000 集 = 4000+ 个节点,
   而每个 tile 还要做两次 O(n) 的 watchedEpisodes.includes(...) —— 点进去要卡一下,
   点一个格子更卡. 而站内绝大多数是 12/13/24/25/26 集的季番, 给它们加一个分页控件
   只是多一层要点的东西, 所以**超过阈值才分页**.

   只是把**渲染**切片, episodes 保持全量 —— 这样一来「已看 N / M」的分母(M 是全量)、
   toggleEp(ep.sort) 的集号语义、watched 高亮(按 ep.sort 比)三处口径一个字都不用动.
   追番进度与剧集列表本来就是解耦的: 高亮的是"这一集看过没有", 与它现在在第几页无关.

   阈值卡的是**实到条数**(episodes.length)而不是条目声明的 totalEpisodes ——
   两者已知不相等(episode 表只有被点开过的番才有剧集, 声明值会虚高),
   卡声明值会出现"说 500 集所以给个分页控件, 实际只取到 12 集"的空控件. */
const EPISODE_PAGE_THRESHOLD = 100
const EPISODE_PAGE_SIZE = 50
const epPage = ref(1)
const epSectionTop = ref(null)
const epPaginated = computed(() => episodes.value.length > EPISODE_PAGE_THRESHOLD)
const epTotalPages = computed(() => epPaginated.value
  ? Math.max(1, Math.ceil(episodes.value.length / EPISODE_PAGE_SIZE))
  : 1)
const visibleEpisodes = computed(() => {
  if (!epPaginated.value) return episodes.value
  const start = (epPage.value - 1) * EPISODE_PAGE_SIZE
  return episodes.value.slice(start, start + EPISODE_PAGE_SIZE)
})
const reviews = ref([])
const ratingStats = ref({ average: 0, count: 0, distribution: Array(10).fill(0) })

/* ── 评论的排序与点赞 ──

   排序是**组件本地状态**, 不进 URL —— 与同一页的剧集分页(epPage)是同一个做法.
   它是展示偏好, 不是可分享的筛选条件: 把 sort=hot 写进地址栏之后, 任何一次刷新、
   回退、以及转发出去的链接都会停在热度序上, 而详情页的评论列表没有分页控件,
   热度序会让「我刚发的那条去哪了」变得无解 —— 所以默认永远是时间序. */
const reviewSort = ref(REVIEW_SORT_CREATED)

/* 「谁赞了」展开后的名单, 按评论 id 存: { open, loading, total, names }.
   拉过一次就留着(收起再展开不重拉), 但**每次重新加载评论列表都要清掉** ——
   里面存的是那批数据的快照, 列表换了(切排序、发/删评论)之后它就过期了. */
const likerBox = ref({})

/* 正在发点赞请求的那些评论 id.
   不加这道闸的话连点会并发发出多个请求, 而它们的响应**不保证按发出的顺序回来**:
   后到的旧响应会把计数写回一个过期的值, 于是数字卡在那儿 —— 界面上没有任何异常,
   再刷新一次才对. 顺带也省掉"点 N 下打 N 个请求". */
const likeBusy = ref({})

const watchedEpisodes = ref([])
const heat = ref(null)
const loading = ref(true)
const relatedAnime = ref([])
const coverFailed = ref(false)

/** 那一排"评分/排名/总集数/年份/类型"里有没有任何一格有值. 全空时整排不渲染,
 *  免得留下一个空容器和它的 18px 下边距 */
const hasStats = computed(() => Boolean(
  subject.value?.rating?.score || subject.value?.rating?.rank ||
  subject.value?.totalEpisodes || subject.value?.date || subject.value?.platform
))

/** 热度三项之和. 全零 = 还没有人碰过这部番, 整行不显示 —— 见模板里那段注释 */
const heatTotal = computed(() => heat.value
  ? (heat.value.wantToWatch || 0) + (heat.value.watching || 0) + (heat.value.watched || 0)
  : 0)
const error = ref('')

const maxProgress = computed(() => subject.value?.totalEpisodes || 999)

/** 提交前把进度夹回合法范围.
 *
 *  改前这个输入框连 min 都没有, 而且原样提交: 手打 -5 会被后端 @Min(0) 拒掉,
 *  但用户拿到的只是一句「保存失败」—— 输入框里那个 -5 还在, 看不出哪里不对;
 *  打 999 则更糟: 后端收下了, 于是进度变成 999/12, 进度条还是 100%,
 *  数字却永远停在那儿. 所以负数按 0 处理(它表达的是"记不清了", 不是"倒着看"),
 *  超出总集数按总集数封顶. 非数字(输入框清空时 v-model.number 给的是空串)也归 0. */
function clampProgress(value) {
  const n = Number(value)
  if (!Number.isFinite(n) || n < 0) return 0
  const total = subject.value?.totalEpisodes
  return total ? Math.min(Math.floor(n), total) : Math.floor(n)
}

const coverImg = computed(() => coverFailed.value ? fallbackImg : (subject.value?.images?.large || subject.value?.images?.common || fallbackImg))
const heroBg = computed(() => coverFailed.value ? null : (subject.value?.images?.large || subject.value?.images?.common || null))

const statusOptions = [
  { label: '想看', value: 'want_to_watch' },
  { label: '在看', value: 'watching' },
  { label: '看过', value: 'watched' },
  { label: '搁置', value: 'on_hold' },
  { label: '抛弃', value: 'dropped' },
]
const trackForm = reactive({ id: null, status: 'want_to_watch', progress: 0, score: 0 })
const myReview = reactive({ id: null, rating: 0, content: '' })

function onCoverError(){ coverFailed.value = true }
function fmt(d){ return d ? new Date(d).toLocaleDateString('zh-CN') : '' }
function barPct(cnt, dist){ const m = Math.max(...dist,1); return Math.max(2, (cnt/m)*100) }

async function load(){
  // 重试要能回到「加载中」, 否则点了重试界面没有任何变化(load 原先只在挂载时跑,
  // loading 的初值就是 true, 所以不需要自己置位)
  loading.value = true
  error.value = ''
  epPage.value = 1  // 重试会重跑 load(): 不复位的话可能停在新列表里不存在的那一页
  try{
    const [dr,er,sr,rr] = await Promise.all([getAnimeDetail(sid),getEpisodes(sid),getRatingStats(sid),getSubjectReviews(sid,userStore.user?.id||0,reviewSort.value)])
    subject.value = dr.data.data
    episodes.value = er.data.data||[]
    ratingStats.value = sr.data.data||{average:0,count:0,distribution:Array(10).fill(0)}
    applyReviews(rr.data.data)

    // 相关番剧: 拿这部番的**第一个标签**, 去看同标签下的高分作品.
    //
    // 改前走的是 getByTag —— 那条接口按播出日倒序、封顶 50 条、取前 8. 而 tags[0] 是
    // 票数最高的那个标签, 也就是**最泛**的那个: 命运石之门的 tags[0] 是「科幻」,
    // 全站 25 万部挂着它. 于是"同标签 + 按日期倒序 + 取 8"实际等于"最近更新的 8 部
    // 科幻" —— 与这部番本人毫无关系, 而它挂在「相关推荐」这个标题底下.
    //
    // 改成按加权评分取: 仍然是"最好的科幻"而不是"最相关的", 但至少从"最新的一批"
    // 变成了"最好的几部". 真正的相关性要按多标签重合度算, 那是后端的活, 这一轮不做.
    const tags = subject.value?.tags
    if (tags?.length > 0) {
      try {
        // 参数名是 genre 不是 tag: 分类浏览页把 `/filter` 的标签参数从"单个 tag"
        // 改成了四个维度(genre/medium/source/region), 且**没留 tag 别名** ——
        // 留一个同义参数会让"同一个筛选有两种表达"长期存在. 这里传的是原始标签名
        // (不是分类页那套 slug 词表): 详情页的标签本来就来自库, 直接对上后端.
        const tr = await getFiltered({ genre: tags[0].name, sort: 'rating', page: 1, limit: 8 })
        relatedAnime.value = (tr.data.data?.list || []).filter(a => a.id !== sid).slice(0, 8)
      } catch (e) { /* 相关推荐拉不到不影响正文 */ }
    }

    if(userStore.loggedIn){
      const [tk,mr] = await Promise.all([getTrackingStatus(sid),getMyReview(sid)])
      const td=tk.data.data; if(td?.tracked){ trackForm.id=td.id; trackForm.status=td.status; trackForm.progress=td.progress||0; trackForm.score=td.score||0 }
      const rd=mr.data.data; if(rd?.exists){ myReview.id=rd.id; myReview.rating=rd.rating; myReview.content=rd.content||'' }
      try{ const [w,h] = await Promise.all([getWatchedEpisodes(sid),getAnimeHeat(sid)]); watchedEpisodes.value=w.data.data||[]; heat.value=h.data.data||null }catch(e){}
    }else{ try{ const h=await getAnimeHeat(sid); heat.value=h.data.data||null }catch(e){} }
  }catch(e){
    // 改前只 console.error, 于是 subject 保持 null, 页面落到「番剧不存在或已下架」
    error.value = loadErrorMessage(e, '加载番剧')
  }
  loading.value=false
}

async function toggleEp(n){
  if(!userStore.loggedIn) return
  try{ await toggleEpisode(sid,n); const i=watchedEpisodes.value.indexOf(n); if(i>=0) watchedEpisodes.value.splice(i,1); else watchedEpisodes.value.push(n) }catch(e){}
}

/**
 * 翻到剧集列表的另一页, 并把剧集区送回视野.
 *
 * 不这么做的话, 用户点完「下一页」视口停在分页按钮那一行 —— 也就是新一页 50 张
 * 格子的**末尾**, 看到的是中间而不是开头.
 *
 * block 用 'start' 而不是 'nearest': 点分页按钮时剧集区**已经部分可见**,
 * 'nearest' 的语义是"已经看得见就不动", 于是整个调用变成空操作. 这里要的是
 * "把这一区从头给我看".
 *
 * 刻意不传 behavior: 交给 base.css 的 html{scroll-behavior:smooth}, 而它在
 * prefers-reduced-motion 下被改成 auto —— "减少动效"的用户自动得到瞬移,
 * 不用在 JS 里再查一次媒体查询.
 *
 * 落点会不会被 sticky 的导航栏盖住由 CSS 管: .ep-tile-grid 上有 scroll-margin-top.
 */
function changeEpPage(page){
  epPage.value = page
  epSectionTop.value?.scrollIntoView({ block: 'start' })
}
async function quickTrack(){
  trackForm.status='watching'; trackForm.progress=0; trackForm.score=0
  await saveTrack()
}
async function saveTrack(){
  if(!userStore.loggedIn) return
  // 夹一次再发, 顺便把输入框里的数字改回夹过之后的值 ——
  // 否则界面上还显示着用户填的 999, 而库里存的是 12, 两边对不上
  trackForm.progress = clampProgress(trackForm.progress)
  try{ const r=await saveTracking({subjectId:sid,status:trackForm.status,progress:trackForm.progress,score:trackForm.score}); trackForm.id=r.data.data?.id; toast('已保存','success') }catch(e){toast('保存失败','error')}
}
async function removeTrack(){
  if(!confirm('取消追番？')) return
  try{ await deleteTracking(sid); trackForm.id=null; trackForm.status='want_to_watch'; trackForm.progress=0; trackForm.score=0; toast('已取消','info') }catch(e){toast('操作失败','error')}
}
async function submitReview(){
  if(!userStore.loggedIn||myReview.rating<=0){toast('请评分','warning');return}
  try{ const r=await saveReview({subjectId:sid,rating:myReview.rating,content:myReview.content}); myReview.id=r.data.data?.id; toast('已提交','success')
    await reloadReviews() }catch(e){toast('失败','error')}
}
async function deleteMyReview(){
  if(!confirm('删除评论？')) return
  try{ await delReviewApi(myReview.id); myReview.id=null; myReview.rating=0; myReview.content=''
    await reloadReviews() }catch(e){toast('失败','error')}
}

/**
 * 把一批评论装进 reviews, 顺带丢掉「谁赞了」的旧名单.
 *
 * 两件事必须一起做: 名单里存的是某一条评论**那一刻**的点赞人, 而列表一换
 * (切排序、发/删评论、重试加载)那份快照就过期了 —— 留着它, 展开后看到的是
 * 上一批数据里的名字, 与旁边那个赞数对不上, 而且没有任何东西会报错.
 */
function applyReviews(list){
  reviews.value = list || []
  likerBox.value = {}
}

/** 重新拉评论列表(保持当前排序)与评分统计. 发/删评论之后走这里 */
async function reloadReviews(){
  const [rr,sr] = await Promise.all([
    getSubjectReviews(sid, userStore.user?.id||0, reviewSort.value),
    getRatingStats(sid),
  ])
  applyReviews(rr.data.data)
  ratingStats.value = sr.data.data||{average:0,count:0,distribution:Array(10).fill(0)}
}

/**
 * 切「最新 / 最热」.
 *
 * 点了同一个不重发(那个按钮本来就是选中态). 失败时**不把开关留在新状态上** ——
 * 界面显示"最热"而列表还是时间序的话, 用户会以为热度排序坏了, 而真相是这次请求
 * 没成功; 退回去至少与眼睛看到的那份列表是一致的.
 */
async function setReviewSort(next){
  if(reviewSort.value === next) return
  const previous = reviewSort.value
  reviewSort.value = next
  try{ await reloadReviews() }
  catch(e){ reviewSort.value = previous; toast('排序切换失败','error') }
}

/**
 * 点赞 / 取消点赞.
 *
 * 未登录时不拦着按钮、也不弹提示 —— 直接送去登录页(与这一页「+ 追番」的做法一致),
 * 登录后的回跳由 router 的 loginRedirect 兜底, 回到这儿还能接着点.
 *
 * 计数与选中态**都由服务端回的那个数字覆盖**, 不在本地 +1 猜: 幂等路径(已经赞过
 * 再点一次)在服务端的计数与"本地 +1"根本不是一回事, 猜出来的数字会一直错下去,
 * 直到下次刷新. 服务端的 {liked, likeCount} 就是为这个回的.
 */
async function toggleLike(review){
  if(!userStore.loggedIn){ router.push('/login'); return }
  if(likeBusy.value[review.id]) return
  const wanted = !review.likedByMe
  likeBusy.value[review.id] = true
  try{
    const res = wanted ? await likeReview(review.id) : await unlikeReview(review.id)
    const d = res.data.data || {}
    review.likedByMe = d.liked ?? wanted
    if(typeof d.likeCount === 'number') review.likeCount = d.likeCount
    // 名单里少/多了一个人: 展开着的话重新拉一次, 否则收起它 ——
    // 留着一份"没有我"的旧名单比不显示更糟
    const box = likerBox.value[review.id]
    if(box){ box.open ? fetchLikers(review.id) : delete likerBox.value[review.id] }
  }catch(e){ toast('操作失败','error') }
  finally{ delete likeBusy.value[review.id] }
}

/** 拉这条评论的点赞人名单, 填进那个盒子 */
async function fetchLikers(reviewId){
  /* 必须从 likerBox 里**读回来**再改, 不能拿着赋值时那个对象的引用去改.
     存进去的是个普通对象, 而普通对象是"读的时候"才被包成响应式代理的 ——
     直接改原始对象不会触发依赖, 表现是「点了没反应」, 而请求其实成功了. */
  const box = likerBox.value[reviewId]
  if(!box) return
  box.loading = true
  try{
    const res = await getReviewLikers(reviewId)
    const d = res.data.data || {}
    box.names = d.list || []
    box.total = d.total || 0
  }catch(e){
    // 名单拉不到不该把这行留成空白: 收起它, 赞数还在原地, 用户再点一次就是重试
    box.open = false
    toast('加载失败','error')
  }finally{
    box.loading = false
  }
}

/** 展开/收起「谁赞了」. 第一次展开才去拉名单; 收起再展开不重拉 */
async function toggleLikers(review){
  const existing = likerBox.value[review.id]
  if(existing){ existing.open = !existing.open; return }
  likerBox.value[review.id] = { open: true, loading: true, total: 0, names: [] }
  await fetchLikers(review.id)
}

onMounted(load)
</script>

<style scoped>
/* ====== HERO ====== */
.d-hero { position:relative; min-height:440px; display:flex; align-items:center; overflow:hidden; }
.d-hero-bg{ position:absolute; inset:0; }
.d-hero-bg-img{ width:100%; height:100%; object-fit:cover; filter:blur(24px) brightness(.22); transform:scale(1.2); }
/* 头图上是深色孤岛(见 tokens.css), 所以这层纱**不跟主题变**。
   这两组 rgba 就是 --hero-bg(#120f0f) 和 --hero-bg-2(#221c1c) 加上透明度 ——
   CSS 没法给 var() 里的十六进制再叠一个 alpha(除非用 color-mix, 那要看构建
   目标的浏览器支持), 所以只能在这儿写死。改 tokens 里那两个值时记得同步这里。
   改前是紫→靛→紫(#0c0418/#1a1040/#140824), 上一版配色的残留。 */
.d-hero-mesh{ position:absolute; inset:0; background: linear-gradient(160deg, rgba(18,15,15,.94) 0%, rgba(34,28,28,.8) 40%, rgba(18,15,15,.9) 70%, rgba(18,15,15,.95) 100%); }
.d-hero-content{ position:relative; z-index:2; display:flex; gap:40px; align-items:flex-start; padding-top:40px; padding-bottom:40px; }
.d-hero-left{ flex-shrink:0; display:flex; flex-direction:column; align-items:center; gap:16px; }
.d-cover-wrap{ position:relative; }
.d-cover{ width:220px; border-radius:12px; aspect-ratio:3/4; object-fit:cover; box-shadow:0 16px 64px rgba(0,0,0,.5); border:2px solid rgba(255,255,255,.06); }
/* 这两个角标压在封面上 → 用 --cover-* 那组, 不跟主题变.
   一深一浅是有意的: 评分是"读一个数", 排名是"贴一个标", 权重不同。 */
/* 这两处原本是 800 —— 而正文字体最粗只到 700, 800 是伪粗体合成出来的。
   14px/12px 属于小字号, 一律用真的 700; 20px 以上才换显示体(见下面 .ds-val)。 */
.d-cover-score{ position:absolute; bottom:-8px; right:-8px; padding:4px 12px; border-radius:12px; background:var(--cover-scrim); color:var(--cover-star); font-size:14px; font-weight:700; border:1.5px solid rgba(255,255,255,.1); backdrop-filter:blur(8px); }
.d-cover-rank{ position:absolute; top:-8px; left:-8px; padding:4px 10px; border-radius:8px; background:var(--cover-fg); color:var(--cover-ink); font-size:12px; font-weight:700; }
.d-hero-actions{ width:100%; }
/* 这个按钮在**头图上**(深色孤岛里), 不是在普通卡片上 —— 所以它不能用
   --primary: 浅色主题下 --primary 是近黑, 近黑的按钮压在近黑的头图上会糊成一片。
   头图上的主按钮一律"浅底 + 深字"。注意它跟下面 .d-btn-save 是两回事:
   那个在 .d-track-bar 里(background:var(--card)), 是正常页面上的按钮。 */
.d-btn-track{ width:100%; padding:12px; border-radius:10px; font-size:15px; font-weight:700; cursor:pointer; border:none; color:var(--cover-ink); background:var(--cover-fg); transition:all var(--transition); font-family:inherit; }
.d-btn-track:hover{ background:#fff; transform:translateY(-1px); }
/* 已追番: 由"实心"改成"描边"。--primary 是墨色, 用它当文字色跟 --text 没有
   区别, 选中态会消失 —— 所以状态改由填充方式表达, 不靠色相。 */
.d-btn-track.tracking{ background:rgba(255,255,255,.14); border:1.5px solid rgba(255,255,255,.4); color:var(--cover-fg); }

.d-hero-right{ flex:1; min-width:0; padding-top:8px; }
.d-title{ font-size:34px; font-weight:900; color:var(--cover-fg); line-height:1.2; margin-bottom:4px; text-shadow:0 2px 16px rgba(0,0,0,.5); }
.d-subtitle{ font-size:14px; color:rgba(255,255,255,.35); margin-bottom:20px; }
.d-stats{ display:flex; gap:28px; margin-bottom:18px; }
.d-stat{ display:flex; flex-direction:column; align-items:center; }
/* 头图上那排"评分/排名/总集数"的大数字 —— 20px、显示级, 所以换显示体,
   那里 800 是真字重(改前 800 压在正文字体上 = 伪粗体) */
.ds-val{ font-family:var(--font-display); font-size:20px; font-weight:800; color:var(--cover-fg); }
.ds-lbl{ font-size:11px; color:rgba(255,255,255,.35); margin-top:2px; }
.d-tags{ display:flex; gap:6px; flex-wrap:wrap; margin-bottom:16px; }
.d-tag{ padding:5px 14px; border-radius:16px; background:rgba(255,255,255,.08); color:rgba(255,255,255,.7); font-size:12px; font-weight:500; border:1px solid rgba(255,255,255,.06); backdrop-filter:blur(4px); }
.d-summary{ font-size:14px; line-height:1.9; color:rgba(255,255,255,.5); margin-bottom:14px; display:-webkit-box; -webkit-line-clamp:4; -webkit-box-orient:vertical; overflow:hidden; }
.d-heat{ display:flex; gap:20px; font-size:12px; color:rgba(255,255,255,.35); }

/* ====== TRACK BAR ====== */
.d-track-bar{ display:flex; align-items:center; gap:16px; flex-wrap:wrap; padding:16px 20px; background:var(--card); border-radius:var(--radius); border:1px solid var(--card-border); margin-bottom:20px; }
.track-input-row{ display:flex; align-items:center; gap:6px; font-size:13px; color:var(--text-secondary); }
.track-input-row input{ width:56px; padding:5px 6px; border:1.5px solid var(--input-border); border-radius:6px; text-align:center; background:var(--input-bg); color:var(--text); font-size:13px; font-family:inherit; }
.track-input-row input:focus{ border-color:var(--primary); outline:none; }
.d-btn-save{ padding:8px 22px; border-radius:8px; border:none; background:var(--primary); color:var(--primary-foreground); font-size:13px; font-weight:600; cursor:pointer; transition:all var(--transition); font-family:inherit; }
.d-btn-save:hover{ background:var(--primary-hover); }
.d-btn-ghost{ padding:8px 22px; border-radius:8px; border:1.5px solid var(--border); background:transparent; color:var(--text-secondary); font-size:13px; cursor:pointer; font-family:inherit; }

/* ====== SECTIONS ====== */
/* 标题栏(.d-section-hd / .d-section-extra)已经收进 SectionHeader 组件 ——
   它那三个分区原本各写一遍同样的东西, 而 Home 那边还有两种别的写法。
   "已看 3/12" 的弱化处理一并交给 .sec-extra。 */
.d-section{ margin-bottom:32px; }

/* ====== EPISODE TILES ====== */
/* scroll-margin-top 是必须的, 不是美化: .navbar 是 position:sticky; top:0; height:64px,
   不留这段高度的话翻页时 block:'start' 会把「剧集列表」标题压到导航栏底下.
   80 = 64 + 16 呼吸. 滚动后 navbar 会缩到 48px, 多出的 32px 空隙可以接受 ——
   比被盖住强. */
.ep-tile-grid{ display:grid; grid-template-columns:repeat(auto-fill,minmax(100px,1fr)); gap:10px; scroll-margin-top:80px; }
.ep-tile{
  position:relative; display:flex; flex-direction:column; align-items:center; gap:6px;
  padding:16px 8px 12px; border-radius:var(--radius-sm);
  border:1.5px solid var(--card-border); background:var(--card);
  cursor:pointer; transition:all var(--transition); font-family:inherit;
}
.ep-tile:hover{ border-color:var(--primary); background:var(--card-hover); transform:translateY(-2px); }
.ep-tile.watched{ background:var(--primary-soft); border-color:var(--primary-line); }
.ep-tile-num{ font-family:var(--font-display); font-size:20px; font-weight:800; color:var(--text-secondary); font-variant-numeric:tabular-nums; }
/* 看过 = 满墨。--primary 是墨色, 拿它当"强调文字"跟 --text 没差别,
   而这个状态需要跟未看的 --text-secondary 拉开距离 */
.ep-tile.watched .ep-tile-num{ color:var(--text); }
.ep-tile-name{ font-size:11px; color:var(--text-muted); text-align:center; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; max-width:100%; }
.ep-check{ position:absolute; top:4px; right:6px; font-size:11px; color:var(--success); }

/* ====== RELATED ====== */
.related-scroll{ display:flex; gap:14px; overflow-x:auto; padding-bottom:4px; scrollbar-width:none; }
.related-scroll::-webkit-scrollbar{ display:none; }
.related-card{ width:150px; flex-shrink:0; cursor:pointer; transition:transform var(--transition); }
.related-card:hover{ transform:translateY(-4px); }
.rc-cover{ aspect-ratio:3/4; border-radius:var(--radius-sm); overflow:hidden; background:var(--bg-secondary); position:relative; margin-bottom:6px; }
.rc-cover img{ width:100%; height:100%; object-fit:cover; }
.rc-score{ position:absolute; bottom:4px; right:4px; padding:2px 6px; border-radius:4px; background:var(--cover-scrim); color:var(--cover-star); font-size:10px; font-weight:700; }
.rc-title{ font-size:13px; font-weight:600; color:var(--text); overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
.rc-year{ font-size:11px; color:var(--text-muted); }

/* ====== REVIEWS ====== */
.my-review{ background:var(--bg-secondary); border-radius:var(--radius); padding:16px; margin-bottom:20px; border:1px solid var(--border); }
.mr-stars{ display:flex; gap:2px; margin-bottom:10px; }
.mr-star{ background:none; border:none; cursor:pointer; font-size:24px; color:var(--text-muted); transition:all .1s; padding:0; }
.mr-star:hover{ transform:scale(1.25); }
.mr-star.on{ color:var(--star); }
.mr-input{ width:100%; padding:10px 12px; border:1.5px solid var(--input-border); border-radius:8px; background:var(--input-bg); color:var(--text); font-size:13px; resize:vertical; font-family:inherit; }
.mr-input:focus{ border-color:var(--primary); outline:none; }

.rate-bars{ display:flex; flex-direction:column; gap:4px; margin-bottom:20px; }
.rate-bar-row{ display:flex; align-items:center; gap:8px; font-size:12px; }
.rbr-label{ width:16px; text-align:center; color:var(--text-muted); font-weight:700; }
.rbr-track{ flex:1; height:5px; background:var(--bg-secondary); border-radius:3px; overflow:hidden; }
.rbr-fill{ height:100%; border-radius:3px; background:var(--primary); transition:width .6s var(--ease); }
.rbr-cnt{ width:22px; text-align:right; color:var(--text-muted); font-size:11px; }

.review-list{ display:flex; flex-direction:column; }
.rv-item{ display:flex; gap:12px; padding:16px 0; border-bottom:1px solid var(--border); }
.rv-avatar{ width:36px; height:36px; border-radius:50%; background:var(--primary); color:var(--primary-foreground); display:flex; align-items:center; justify-content:center; font-weight:700; font-size:14px; flex-shrink:0; }
.rv-body{ flex:1; min-width:0; }
.rv-top{ display:flex; align-items:center; gap:10px; margin-bottom:4px; }
.rv-username{ font-weight:700; font-size:13px; color:var(--text); }
.rv-stars{ color:var(--star); font-size:13px; letter-spacing:1px; }
.rv-time{ font-size:11px; color:var(--text-muted); margin-left:auto; }
.rv-text{ font-size:14px; line-height:1.7; color:var(--text-secondary); }

/* ── 排序开关 / 点赞 / 谁赞了 ── */
.rv-sort{ display:flex; justify-content:flex-end; gap:6px; margin-bottom:6px; }
.rv-sort-btn{
  padding:4px 12px; border-radius:999px; border:1.5px solid var(--border);
  background:transparent; color:var(--text-muted); font-size:12px; font-weight:600;
  cursor:pointer; font-family:inherit; transition:all var(--transition);
}
.rv-sort-btn:hover{ color:var(--text); border-color:var(--primary-line); }
/* 选中态是「洗色底 + 描边 + 满墨字」, 不靠色相 —— --primary 是墨色, 拿它当强调
   文字跟 --text 没有区别, 选中态会消失(tokens.css 顶部那条规矩). */
.rv-sort-btn.active{ background:var(--primary-soft); border-color:var(--primary-line); color:var(--text); }

/* margin-left:-8px 把按钮的**字形**对齐到上面正文的左边缘: 按钮自己要有内边距
   才点得舒服, 而那 8px 会让心形看着比正文缩进去一截. */
.rv-actions{ display:flex; align-items:center; gap:12px; margin-top:8px; margin-left:-8px; }
.rv-act{
  display:inline-flex; align-items:center; gap:5px; padding:3px 8px;
  border-radius:999px; border:1.5px solid transparent; background:transparent;
  color:var(--text-muted); font-size:12px; font-weight:600; cursor:pointer;
  font-family:inherit; font-variant-numeric:tabular-nums;
  transition:color var(--transition), background var(--transition), border-color var(--transition);
}
.rv-act:hover{ color:var(--text); background:var(--primary-soft); }
/* 已赞 = 满墨 + 洗色底 + 描边. 心形本身也由 regular 换成 fill(模板里那个 :weight) ——
   两套主题下都是"底变浅/变深 + 图标变实 + 字变满墨"三重差别, 只靠其中任何一样
   在浅色主题下都太轻. */
.rv-act.on{ color:var(--text); background:var(--primary-soft); border-color:var(--primary-line); }
.rv-act:disabled{ cursor:default; opacity:.55; }
.rv-act-quiet{ font-weight:500; }
.rv-likers{ display:flex; flex-wrap:wrap; gap:6px; margin-top:8px; }
.rv-liker{
  padding:2px 10px; border-radius:999px; background:var(--tag-bg);
  color:var(--text-secondary); font-size:12px;
}
.rv-likers-hint{ color:var(--text-muted); font-size:12px; }
.no-rev{ text-align:center; padding:30px; color:var(--text-muted); font-size:14px; }
.no-rev a{ color:var(--primary); font-weight:600; }
.not-found{ text-align:center; padding:80px; color:var(--text-muted); font-size:16px; }

@media(max-width:768px){
  .d-hero{ min-height:auto; }
  .d-hero-content{ flex-direction:column; align-items:center; text-align:center; gap:24px; padding-top:24px; padding-bottom:24px; }
  .d-cover{ width:150px; }
  .d-title{ font-size:24px; }
  .d-stats{ justify-content:center; gap:18px; }
  .ds-val{ font-size:16px; }
  .d-tags{ justify-content:center; }
  .d-heat{ justify-content:center; }
  .d-track-bar{ flex-direction:column; align-items:stretch; }
  .ep-tile-grid{ grid-template-columns:repeat(auto-fill,minmax(72px,1fr)); gap:6px; }
  .ep-tile{ padding:10px 4px 8px; }
  .ep-tile-num{ font-size:16px; }
  .related-card{ width:120px; }
}
</style>
