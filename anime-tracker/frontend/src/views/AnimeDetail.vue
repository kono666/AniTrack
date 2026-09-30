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
          <div class="d-stats">
            <div class="d-stat"><span class="ds-val">{{ subject.rating?.score ? subject.rating.score.toFixed(1) : '-' }}</span><span class="ds-lbl">评分</span></div>
            <div class="d-stat"><span class="ds-val">#{{ subject.rating?.rank || '-' }}</span><span class="ds-lbl">排名</span></div>
            <div class="d-stat"><span class="ds-val">{{ subject.totalEpisodes || '?' }}</span><span class="ds-lbl">总集数</span></div>
            <div class="d-stat"><span class="ds-val">{{ subject.date?.substring(0,4) || '-' }}</span><span class="ds-lbl">年份</span></div>
            <div class="d-stat"><span class="ds-val">{{ subject.platform || 'TV' }}</span><span class="ds-lbl">类型</span></div>
          </div>
          <div class="d-tags" v-if="subject.tags?.length">
            <span v-for="tag in subject.tags.slice(0, 6)" :key="tag.name" class="d-tag">{{ tag.name }}</span>
          </div>
          <p class="d-summary">{{ subject.summary || '暂无简介' }}</p>
          <div class="d-heat" v-if="heat">
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
        <div v-if="episodes.length > 0" class="ep-tile-grid">
          <button
            v-for="ep in episodes" :key="ep.id"
            class="ep-tile"
            :class="{ watched: watchedEpisodes.includes(ep.sort) }"
            @click="toggleEp(ep.sort)"
          >
            <span class="ep-tile-num">{{ ep.sort }}</span>
            <span class="ep-tile-name">{{ ep.nameCn || ep.name || '第'+ep.sort+'集' }}</span>
            <span v-if="watchedEpisodes.includes(ep.sort)" class="ep-check">✓</span>
          </button>
        </div>
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
import { useRoute } from 'vue-router'
import { useUserStore } from '../stores/user'
import {
  getAnimeDetail, getEpisodes, getRatingStats, getSubjectReviews,
  getMyReview, saveReview, deleteMyReview as delReviewApi,
  getTrackingStatus, saveTracking, deleteTracking,
  getWatchedEpisodes, toggleEpisode, getAnimeHeat, getByTag
} from '../api'
import { loadErrorMessage } from '../utils/loadError'
import { COVER_FALLBACK_CARD as fallbackImg } from '../utils/fallbackImg'
import { useToast } from '../composables/useToast'
import SectionHeader from '../components/SectionHeader.vue'
import LoadingSpinner from '../components/LoadingSpinner.vue'
import EmptyState from '../components/EmptyState.vue'

const route = useRoute()
const userStore = useUserStore()
const { show: toast } = useToast()
const sid = Number(route.params.id)

const subject = ref(null)
const episodes = ref([])
const reviews = ref([])
const ratingStats = ref({ average: 0, count: 0, distribution: Array(10).fill(0) })
const watchedEpisodes = ref([])
const heat = ref(null)
const loading = ref(true)
const relatedAnime = ref([])
const coverFailed = ref(false)
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
  try{
    const [dr,er,sr,rr] = await Promise.all([getAnimeDetail(sid),getEpisodes(sid),getRatingStats(sid),getSubjectReviews(sid,userStore.user?.id||0)])
    subject.value = dr.data.data
    episodes.value = er.data.data||[]
    ratingStats.value = sr.data.data||{average:0,count:0,distribution:Array(10).fill(0)}
    reviews.value = rr.data.data||[]

    const tags = subject.value?.tags
    if(tags?.length>0){ try{ const tr=await getByTag(tags[0].name); relatedAnime.value=(tr.data.data||[]).filter(a=>a.id!==sid).slice(0,8) }catch(e){} }

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
    const [rr,sr]=await Promise.all([getSubjectReviews(sid,userStore.user.id),getRatingStats(sid)]); reviews.value=rr.data.data||[]; ratingStats.value=sr.data.data||{average:0,count:0,distribution:Array(10).fill(0)} }catch(e){toast('失败','error')}
}
async function deleteMyReview(){
  if(!confirm('删除评论？')) return
  try{ await delReviewApi(myReview.id); myReview.id=null; myReview.rating=0; myReview.content=''
    const [rr,sr]=await Promise.all([getSubjectReviews(sid,userStore.user.id),getRatingStats(sid)]); reviews.value=rr.data.data||[]; ratingStats.value=sr.data.data||{average:0,count:0,distribution:Array(10).fill(0)} }catch(e){toast('失败','error')}
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
.ep-tile-grid{ display:grid; grid-template-columns:repeat(auto-fill,minmax(100px,1fr)); gap:10px; }
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
