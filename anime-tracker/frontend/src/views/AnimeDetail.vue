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
            <!-- 封面右下角那个 ★9.1 **删掉了**(c117)。它与下面 .d-stats 里的 9.1 是
                 **同一个数**, 相距不到 200px 印两遍; 而两处都没有标签, 读的人无从知道
                 这是谁打的分。留下的是有标签的那一处(.ds-lbl「番组评分」)。
                 排名角标保留 —— 它只在这一处出现, 不是重复。 -->
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
            <!-- 「番组评分」不是「评分」: 站内 UI 上从来没出现过「Bangumi」这个词, 而这
                 一页有**四个分**, 其中两个原先都叫「评分」。这个名字是给读的人看的 ——
                 它是上游聚合分, 不是本站在座各位打的分(那是下面评论区的「本站均分」),
                 也不是我自己的追番分(那是追番栏的「追番评分」)。三个名字互不重合,
                 谁是谁一眼能分清。 -->
            <div class="d-stat" v-if="subject.rating?.score"><span class="ds-val">{{ subject.rating.score.toFixed(1) }}</span><span class="ds-lbl">番组评分</span></div>
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
            @click="changeStatus(s.value)">{{ s.label }}</button>
        </div>
        <!-- 进度现在的**主入口是下面那一排剧集格子**(点一下就是一次打卡, 服务端顺手把
             进度推到这一集). 这个数字框留着当次要入口, 因为追番的人常一次看好几集、
             或者先看完后补记 —— 逼着一集集点五次很难受. 文案因此从「进度」改成
             「直接改进度」, 不再暗示它是唯一的路. -->
        <!-- min="0" 与 :max 只是浏览器给的护栏(拖动步进箭头时用), 提交时不算数 ——
             手打一个 999 照样能提交, 所以 saveTrack 里还有一道 clamp. 两道都要:
             只有前者的话手打能绕过去, 只有后者的话用户得先提交才知道自己填错了 -->
        <!-- @input 只是**记下这个框被动过**, 不发请求. 「保存」只把动过的字段发上去,
             没动的一个字都不带 —— 否则本地那份副本一旧, 没碰过的字段就会被静默回退 -->
        <div class="track-input-row">
          <label>直接改进度</label><input type="number" v-model.number="trackForm.progress" min="0" :max="maxProgress" @input="markDirty('progress')" />
          <span>/ {{ totalEpisodesHere || '?' }}</span>
          <!-- 「追番评分」: 这一格是我给这部番打的分, 与 hero 里那个上游聚合分不是
               一回事。改前两处都叫「评分」, 而它们可以差出好几分。 -->
          <label style="margin-left:16px;">追番评分</label><input type="number" v-model.number="trackForm.score" min="1" max="10" @input="markDirty('score')" />
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
          <!-- 「本站均分」: 这是**本站用户在座各位**打的分, 与 hero 里那个上游聚合分
               是两套口径(可以差出好几分), 所以名字必须分开。
               ⚠️ 零人评分时后端给的 average 就是 0.0 —— `ReviewService` 里那句
               `count == 0 ? 0.0 : (double) sum / count`, 不是缺字段。改前这里没有
               v-if, 于是渲染成「均分 ★0」, 读起来是"这部番得了 0 分", 而真相是"还
               没有人打过分"。有分才报分, 没分就明说「暂无评分」。
               ⚠️ 卡片右上角那个「暂无」保留不动(AnimeCard.vue): 网格里每张卡都有角标
               位, 空着会让封面左上重右上轻。全站因此有两种零分表达 —— 网格角标「暂无」,
               其余整项不渲染。 -->
          <template #extra>
            <template v-if="ratingStats.count > 0">本站均分 <PhStar :size="11" weight="fill" />{{ ratingStats.average }}</template>
            <template v-else>暂无评分</template>
          </template>
        </SectionHeader>

        <!-- My Review -->
        <div v-if="userStore.loggedIn" class="my-review">
          <!-- 被管理员移除的那一条: 给一句说明, 不给表单.
               `exists` 与 `removed` 是服务端分开给的两个状态(V14): 少了这一支的话,
               这里会摆出一张空表单 —— 填完点提交, 后端回 400「该作品的评论已被管理员
               移除」, 而用户完全不知道自己写的那条出了什么事. -->
          <p v-if="myReview.removed" class="mr-removed">你在这部番下的评论已被管理员移除。</p>
          <template v-else>
            <div class="mr-stars">
              <button v-for="n in 10" :key="n" class="mr-star" :class="{ on: n <= myReview.rating }" @click="myReview.rating = n">★</button>
            </div>
            <textarea v-model="myReview.content" placeholder="写评论..." rows="2" class="mr-input"></textarea>
            <button class="d-btn-save" @click="submitReview" style="margin-top:8px;">{{ myReview.id ? '更新' : '提交' }}</button>
            <button v-if="myReview.id" class="d-btn-ghost" @click="deleteMyReview" style="margin-left:8px;">删除</button>
          </template>
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
            <div class="rv-avatar">
              <img v-if="r.avatar" :src="r.avatar" :alt="r.username || ''" class="rv-avatar-img" />
              <template v-else>{{ (r.username||'?')[0] }}</template>
            </div>
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
                <button v-if="(r.likeCount || 0) > 0" class="rv-act rv-act-quiet rv-act-likers"
                  @click="toggleLikers(r)">
                  {{ likerBox[r.id]?.open ? '收起' : '谁赞了' }}
                </button>
                <!-- 回复按钮. 两件事由它一个人管: 「展开/收起这批回复」与「我要回复」——
                     拆成两个按钮的话, 一条还没有回复的评论下会并排出现「0 条回复」和
                     「回复」, 而它们其实是同一个动作.
                     计数为 0 时不显示 "0", 只留图标: 满屏的 "0" 是噪音.
                     这一排三个按钮各有一个**自己的**类(rv-act-likers / rv-act-reply /
                     rv-act-report)当抓手. 别让测试去认 rv-act-quiet 那种样式类 ——
                     举报按钮加上它之后, 「一个赞都没有时不摆「谁赞了」」那条用例当场变红,
                     而它想说的其实是"谁赞了不见了", 不是"所有安静的按钮都不见了". -->
                <button class="rv-act rv-act-reply" :class="{ open: replyBox[r.id]?.open }"
                  @click="toggleReplies(r)">
                  <PhArrowBendUpLeft :size="14" />
                  <span v-if="r.replyCount > 0">{{ r.replyCount }}</span>
                </button>
                <!-- 举报. 未登录也照渲染, 点了去登录页(与点赞同一套做法) —— 藏起来的话
                     访客不知道这站能举报. 举报过的按钮点亮(旗子变实心), 再点只是重开
                     面板 —— 服务端对"同一人对同一条评论"是幂等的, 那一下会回
                     「你已经举报过这条评论」, 前端不自己猜一个状态出来. -->
                <button class="rv-act rv-act-quiet rv-act-report"
                  :class="{ on: reportBox[r.id]?.done }" @click="toggleReport(r)">
                  <PhFlag :size="14" :weight="reportBox[r.id]?.done ? 'fill' : 'regular'" />
                  <span>举报</span>
                </button>
              </div>

              <!-- 举报面板. 与回复区一样**就地展开**, 不造弹层(仓库里没有 Modal 组件).
                   四个理由用单选框而不是下拉: 选项少, 摆开来比点两层快, 也把"有哪些
                   理由"直接告诉了用户. radio 的 name 必须**逐条评论各不相同**, 否则
                   给这条选了理由, 另一条已展开的会被一起清掉. -->
              <div v-if="reportBox[r.id]?.open" class="rv-report">
                <div class="rv-report-title">举报这条评论</div>
                <label v-for="opt in REVIEW_REPORT_REASONS" :key="opt.value" class="rv-report-opt">
                  <input type="radio" :name="'report-reason-' + r.id" :value="opt.value"
                    v-model="reportBox[r.id].reason" />
                  <span>{{ opt.label }}</span>
                </label>
                <textarea v-model="reportBox[r.id].detail" rows="2" maxlength="500"
                  class="rv-report-detail" placeholder="补充说明（选填，最多 500 字）"></textarea>
                <div class="rv-report-btns">
                  <!-- 两道闸: 模板上的 :disabled 与 submitReport 里那句同步的 if.
                       与回复那条路同一条理由 —— :disabled 要等下一个 tick 才落地,
                       同一拍里的第二次点击打在的是还没 disabled 的按钮上. -->
                  <button class="rv-report-send"
                    :disabled="!reportBox[r.id].reason || Boolean(reportBox[r.id].busy)"
                    @click="submitReport(r)">提交举报</button>
                  <button class="rv-report-cancel" :disabled="Boolean(reportBox[r.id].busy)"
                    @click="toggleReport(r)">取消</button>
                </div>
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

              <!-- 回复区. 与评论列表一样**就地展开**, 不造弹层 —— 仓库里没有 Modal
                   组件, 为一个功能造一套体系不划算.

                   访客也能展开: 回复列表本身是公开的(接口与评论列表同一条规矩),
                   只有输入框换成一句登录提示. 藏起来的话, 访客不知道这站有回复.

                   一律懒加载: 打开才发请求. 每条评论都在挂载时拉一遍的话,
                   一页 20 条评论就是 20 个请求, 而其中绝大多数没人会展开. -->
              <div v-if="replyBox[r.id]?.open" class="rp-area">
                <div v-if="replyBox[r.id].loading" class="rp-hint">加载中…</div>
                <div v-else-if="!replyBox[r.id].list.length" class="rp-hint">还没有回复</div>
                <div v-else class="rp-list">
                  <div v-for="p in replyBox[r.id].list" :key="p.id" class="rp-item">
                    <div class="rp-avatar">
                      <img v-if="p.avatar" :src="p.avatar" :alt="p.username || ''" class="rp-avatar-img" />
                      <template v-else>{{ (p.username||'?')[0] }}</template>
                    </div>
                    <div class="rp-body">
                      <div class="rp-top">
                        <span class="rp-username">{{ p.username }}</span>
                        <span v-if="isEdited(p)" class="rp-edited">已编辑</span>
                        <span class="rp-time">{{ fmt(p.createdAt) }}</span>
                      </div>

                      <!-- 编辑态: 整体换成输入框, 而不是在正文上做 contenteditable ——
                           后者要自己管光标、粘贴, 而且改完没法"取消" -->
                      <template v-if="replyEdit[p.id] !== undefined">
                        <textarea v-model="replyEdit[p.id]" rows="2" class="rp-input"></textarea>
                        <div class="rp-edit-actions">
                          <button class="rp-send" @click="saveReplyEdit(r, p)">保存</button>
                          <button class="rp-cancel" @click="cancelReplyEdit(p)">取消</button>
                        </div>
                      </template>

                      <template v-else>
                        <div class="rp-text">{{ p.content }}</div>
                        <div class="rp-actions">
                          <button class="rp-act" :class="{ on: p.likedByMe }"
                            :disabled="Boolean(replyLikeBusy[p.id])"
                            :aria-pressed="p.likedByMe ? 'true' : 'false'" @click="toggleReplyLike(p)">
                            <PhHeart :size="13" :weight="p.likedByMe ? 'fill' : 'regular'" />
                            <span>{{ p.likeCount || 0 }}</span>
                          </button>
                          <button v-if="(p.likeCount || 0) > 0" class="rp-act rp-act-quiet"
                            @click="toggleReplyLikers(p)">
                            {{ replyLikerBox[p.id]?.open ? '收起' : '谁赞了' }}
                          </button>
                          <!-- 「编辑」只有作者有(替别人改话说不通); 「删除」还有评论作者
                               与管理员. 判据在服务端(ReviewReplyService.canDelete), 这里
                               只是别把点不通的按钮摆出来. -->
                          <button v-if="p.isOwner" class="rp-act rp-act-quiet"
                            @click="startReplyEdit(p)">编辑</button>
                          <button v-if="canDeleteReply(r, p)" class="rp-act rp-act-quiet"
                            @click="removeReply(r, p)">删除</button>
                        </div>
                        <div v-if="replyLikerBox[p.id]?.open" class="rp-likers">
                          <span v-if="replyLikerBox[p.id].loading" class="rp-hint">加载中…</span>
                          <span v-else-if="!replyLikerBox[p.id].names.length" class="rp-hint">暂无</span>
                          <template v-else>
                            <span v-for="u in replyLikerBox[p.id].names" :key="u.userId" class="rp-liker">{{ u.username }}</span>
                            <span v-if="replyLikerBox[p.id].total > replyLikerBox[p.id].names.length" class="rp-hint">
                              等共 {{ replyLikerBox[p.id].total }} 人
                            </span>
                          </template>
                        </div>
                      </template>
                    </div>
                  </div>
                </div>

                <div v-if="userStore.loggedIn" class="rp-composer">
                  <textarea v-model="replyDraft[r.id]" rows="2" placeholder="回复..." class="rp-input"></textarea>
                  <button class="rp-send" :disabled="Boolean(replyBusy[r.id])" @click="sendReply(r)">回复</button>
                </div>
                <div v-else class="rp-hint">
                  <router-link to="/login">登录</router-link>后参与讨论
                </div>
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
import PhArrowBendUpLeft from '@icons/PhArrowBendUpLeft.vue.mjs'
import PhFlag from '@icons/PhFlag.vue.mjs'
import { useRoute, useRouter } from 'vue-router'
import { useUserStore } from '../stores/user'
import {
  getAnimeDetail, getEpisodes, getRatingStats, getSubjectReviews,
  getMyReview, saveReview, deleteMyReview as delReviewApi,
  getTrackingStatus, saveTracking, deleteTracking,
  getWatchedEpisodes, toggleEpisode, getAnimeHeat, getFiltered,
  likeReview, unlikeReview, getReviewLikers,
  getReplies, addReply, editReply, deleteReply,
  likeReply, unlikeReply, getReplyLikers,
  reportReview, REVIEW_REPORT_REASONS,
  REVIEW_SORT_CREATED, REVIEW_SORT_HOT
} from '../api'
import { loadErrorMessage } from '../utils/loadError'
import { effectiveEpisodes } from '../utils/episodes'
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

/* ── 回复区的本地状态 ──
   全部按 id 存, 与 likerBox 同一个形状. 分成五个 ref 而不是一个大对象: 它们各自的
   失效时机不同(草稿要留着, 列表快照必须清), 混在一起就没法只清一半. */

/** 每条评论的回复区: { open, loading, list }. 打开才拉(懒加载) */
const replyBox = ref({})
/** 回复框里的草稿, 按**评论** id 存 —— 草稿属于"我要回这条评论", 不属于某条回复 */
const replyDraft = ref({})
/** 正在编辑的回复: replyId -> 草稿正文. 键存在与否就是"这条在编辑态" */
const replyEdit = ref({})
/** 正在发回复的那些**评论** id. 与 likeBusy 是同一条理由, 但这里更要紧:
 *  点赞是幂等的, 而发回复**不是** —— 少这道闸, 连点几下就会真的多出几条一样的回复.
 *
 *  这道忙态喂给两处: 下面的 `if` 与模板里那个 `:disabled`, 于是连点被挡了两遍,
 *  而且**各自都挡得住**. 反向验证量到的正是这个形状: 只删 `if` 是绿的, 只删
 *  `:disabled` 也是绿的, 两个都删才会红(那一跑实测到 2 次调用)。
 *
 *  它们不是同一件事写两遍: `:disabled` 要等 Vue 把属性渲染下去(下一个 tick 才落地),
 *  同一拍里的第二次点击会打在一个**还没 disabled** 的按钮上 —— 那时候接住它的就是
 *  下面这句 if, 而它读的是同步就写好的 ref, 没有那个窗口。*/
const replyBusy = ref({})
/** 回复的赞: 忙态与「谁赞了」盒子, 与评论那两个同构, 只是键换成了回复 id */
const replyLikeBusy = ref({})
const replyLikerBox = ref({})

/* ── 举报 ──
   每条评论一个盒子: { open, reason, detail, busy, done }. 与上面几组同一个形状(按
   评论 id 存), 但**换列表时不清** —— 它装的是两样都跟着"这一条评论"走的东西:
   用户敲了半截的理由与补充说明(草稿), 以及"我已经举报过它了"这个事实. 评论列表
   重新排一次序, 同一条评论还是同一条, 把这两样丢掉都是错的. */
const reportBox = ref({})

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

/**
 * 这一页上「这部番一共多少集」的答案, 用来给进度封顶.
 *
 * 分母的第二项是**本页已经取回来的剧集条数** —— 就是下面那一排瓷砖的个数. 为什么要它:
 * `subject.totalEpisodes` 是条目接口的声明值, 而 Bangumi 对绝大多数条目填的就是 0
 * (实测 29379 条里 29322 条), 于是改前 `totalEpisodes || 999` 一路退到 999,
 * **封顶整个失效** —— 一部 12 集的番能把进度存成 18.
 *
 * 和 Profile 那一页不同(那边刻意只用声明值, 理由写在 Profile 的 totalOf 上), 这里敢用
 * 本地条数是因为**这一页每次打开都会回源刷新剧集列表**, 拿到的不是过期数据。
 * 列表还没回来时它是 0 -> 不封顶, 但那一刻用户也还没看到瓷砖。
 */
const totalEpisodesHere = computed(() =>
  effectiveEpisodes(subject.value?.totalEpisodes, episodes.value.length)
)

const maxProgress = computed(() => totalEpisodesHere.value || 999)

/** 提交前把进度夹回合法范围.
 *
 *  改前这个输入框连 min 都没有, 而且原样提交: 手打 -5 会被后端 @Min(0) 拒掉,
 *  但用户拿到的只是一句「保存失败」—— 输入框里那个 -5 还在, 看不出哪里不对;
 *  打 999 则更糟: 后端收下了, 于是进度变成 999/12, 进度条还是 100%,
 *  数字却永远停在那儿. 所以负数按 0 处理(它表达的是"记不清了", 不是"倒着看"),
 *  超出总集数按总集数封顶. 非数字(输入框清空时 v-model.number 给的是空串)也归 0.
 *
 *  上限取的是 {@link totalEpisodesHere} —— 声明值没有时用本页已取回的剧集条数,
 *  不是宁可退到 999. 改前那一步退让就是"12 集能存 18"的全部原因. */
function clampProgress(value) {
  const n = Number(value)
  if (!Number.isFinite(n) || n < 0) return 0
  const total = totalEpisodesHere.value
  return total ? Math.min(Math.floor(n), total) : Math.floor(n)
}

/**
 * 这一页上「被用户动过、但还没提交」的字段名.
 *
 * 它存在是因为服务端那个接口是**局部更新**: 没传的字段一律保持原值。
 * 改前这里每次都把 status + progress + score 整行发回去, 于是本地那份副本
 * 只要旧了一点点(页面开着没动、两个标签页), 就会把没碰过的字段一起写回旧值 ——
 * 用户改个状态, 进度自己退回去了, 而界面上两处都显示成功。
 *
 * 用普通 Set 而不是 reactive: 它只在 saveTrack 被点的那一刻读一次,
 * 没有任何地方要跟着它重新渲染, 加上响应式只是白白多一层代理。
 */
const dirty = new Set()
function markDirty(field) { dirty.add(field) }

/**
 * 点状态按钮 = **立刻落库**, 与个人页那个下拉同一个规矩。
 *
 * 改前这里只改本地 trackForm.status, 什么都不发, 得再按一次「保存」才作数 ——
 * 而按钮点完就高亮了, 看上去像已经生效。同一件事在两个页面上两套规矩(个人页即改即存),
 * 是这块最容易让人说「有问题」的地方。
 *
 * 先置位再发, 失败退回原值: 状态就五个按钮, 乐观更新比转一圈等待舒服得多,
 * 而失败时界面必须回到真实状态, 不能留一个"看着已生效、其实没存上"的高亮。
 */
async function changeStatus(next){
  if(!userStore.loggedIn || next === trackForm.status) return
  const prev = trackForm.status
  trackForm.status = next
  try{
    await saveTracking({ subjectId: sid, status: next })
    toast('已更新','success')
  }catch(e){
    trackForm.status = prev
    toast(loadErrorMessage(e, '更新'))
  }
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
/* removed 是 V14 起的第三个状态: 写过、但被管理员移除了. 它与"没写过"必须分开 ——
   前者要给一句说明(见上面的模板), 后者才是那张空表单. 判据是服务端的 exists/removed,
   不是"这里有没有填上 id"(被移除时 id 不会填, 那样就与没写过混成一样了). */
const myReview = reactive({ id: null, rating: 0, content: '', removed: false })

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
      const rd=mr.data.data
      // 先落 removed 再判 exists: 被移除的那条**没有** id/rating/content 可用(后端只回
      // exists=true + removed=true), 把它当成"没写过"就会给出一张能填能提交、提交必 400 的表单
      myReview.removed = Boolean(rd?.removed)
      if(rd?.exists && !rd.removed){ myReview.id=rd.id; myReview.rating=rd.rating; myReview.content=rd.content||'' }
      try{ const [w,h] = await Promise.all([getWatchedEpisodes(sid),getAnimeHeat(sid)]); watchedEpisodes.value=w.data.data||[]; heat.value=h.data.data||null }catch(e){}
    }else{ try{ const h=await getAnimeHeat(sid); heat.value=h.data.data||null }catch(e){} }
  }catch(e){
    // 改前只 console.error, 于是 subject 保持 null, 页面落到「番剧不存在或已下架」
    error.value = loadErrorMessage(e, '加载番剧')
  }
  loading.value=false
}

/**
 * 打勾 / 取消打勾**这一集**.
 *
 * 服务端在打勾成功时会顺手把追番进度往前推, 本来没有追番记录还会替我们建一条
 * (见 StatsService#syncProgressOnWatched). 所以这里要跟上两件事, 否则界面与服务端
 * 当场分叉:
 *
 * 1. 进度数字用**与服务端同一个口径**(max)跟着走, 不等重拉;
 * 2. 如果这部番本来没有追番记录(trackForm.id 为空), 说明服务端刚建了一条 ——
 *    此刻 trackForm 里的 id/status 还是空的, 用户接着点「保存」就会拿一个陈旧的
 *    status 覆盖上去, 那条新记录被改成「想看」并**从首页「继续看」里消失**,
 *    而"打勾"和"保存"两步单独看都是成功的. 所以只在这一种情况下重拉一次, 把
 *    id / status / 进度一起对齐; 已经追番的番不必每次往返.
 *
 * 取消打勾**不动进度**: 进度是"看到第几集"的水位线, 把最后一集取消掉不该让它退回去.
 */
async function toggleEp(n){
  if(!userStore.loggedIn) return
  try{
    const r = await toggleEpisode(sid, n)
    if(r?.data?.data?.watched){
      if(!watchedEpisodes.value.includes(n)) watchedEpisodes.value.push(n)
      trackForm.progress = Math.max(trackForm.progress || 0, n)
    }else{
      const i = watchedEpisodes.value.indexOf(n); if(i>=0) watchedEpisodes.value.splice(i,1)
    }
    if(!trackForm.id) await refreshTrackForm()
  }catch(e){
    // 改前这里是空 catch. 服务端没接下这个勾, 而界面上一个字都不说 —— 用户以为打上了,
    // 再点一次却变成了"取消". 打勾失败和保存失败一样, 必须说出来.
    // 传的是"标记"不是"标记失败": loadErrorMessage 自己会补「失败：」(同「加载番剧」).
    toast(loadErrorMessage(e, '标记'))
  }
}

/** 重拉这部番的追番状态, 把 trackForm 的 id / status / 进度对齐到服务端那一份 */
async function refreshTrackForm(){
  try{
    const tk = await getTrackingStatus(sid)
    const td = tk.data?.data
    if(td?.tracked){ trackForm.id=td.id; trackForm.status=td.status; trackForm.progress=td.progress||0; trackForm.score=td.score||0 }
  }catch(e){ /* 对齐失败不影响"这个勾已经打上了"这件事本身, 不打扰用户 */ }
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
/**
 * 「+ 追番」: 建一条在看记录.
 *
 * 只发 status —— progress / score 一个字不带. 改前这里先把 trackForm 清成
 * progress:0 / score:0 再走 saveTrack, 于是"服务端已经有行、本地 id 还是 null"
 * 的那一小段时间里点这个按钮, 会把刚打卡推上去的进度**清零**. c98 起这个窗口是真实存在的:
 * 打完第一个勾由服务端建行, 而本地要等 refreshTrackForm 回来才知道 id,
 * 那一次请求失败的话那个 catch 是静默的.
 *
 * 现在服务端缺席即保持原值, 所以"我想在看这部番"可以真的只发一个 status.
 */
async function quickTrack(){
  try{
    const r = await saveTracking({ subjectId: sid, status: 'watching' })
    const d = r.data?.data
    if(d?.id) trackForm.id = d.id
    trackForm.status = d?.status || 'watching'
    trackForm.progress = d?.progress || 0
    trackForm.score = d?.score || 0
    dirty.clear()
    toast('已追番','success')
  }catch(e){
    toast(loadErrorMessage(e, '追番'))
  }
}

/**
 * 「保存」: 只提交这一页上被动过的字段.
 *
 * 一个字段都没动就不发请求 —— 服务端那边三个字段全是可选的, 一个字段都不带地发过去,
 * 只会在库里凭空建一条默认状态的记录(「没有改动」比这诚实).
 */
async function saveTrack(){
  if(!userStore.loggedIn) return
  if(dirty.size === 0){ toast('没有改动','info'); return }
  const payload = { subjectId: sid }
  if(dirty.has('progress')){
    // 夹一次再发, 顺便把输入框里的数字改回夹过之后的值 ——
    // 否则界面上还显示着用户填的 999, 而库里存的是 12, 两边对不上
    trackForm.progress = clampProgress(trackForm.progress)
    payload.progress = trackForm.progress
  }
  if(dirty.has('score')) payload.score = trackForm.score
  try{
    const r = await saveTracking(payload)
    if(r.data?.data?.id) trackForm.id = r.data.data.id
    dirty.clear()
    toast('已保存','success')
  }catch(e){
    toast(loadErrorMessage(e, '保存'))
  }
}
async function removeTrack(){
  if(!confirm('取消追番？')) return
  try{ await deleteTracking(sid); trackForm.id=null; trackForm.status='want_to_watch'; trackForm.progress=0; trackForm.score=0; dirty.clear(); toast('已取消','info') }catch(e){toast('操作失败','error')}
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
  // 回复那两份快照同理: 「已展开的列表」与「谁赞了」存的都是那批数据的旧样子,
  // 换了列表还留着, 展开后看到的是上一批回复 —— 而它们挂在别人的评论下面.
  //
  // 草稿(replyDraft)**不清**: 那是用户敲进去的字, 只按评论 id 存, 同一条评论换了
  // 个位置也还是同一条. 编辑态(replyEdit)则要清 —— 那些草稿按回复 id 存, 而回复
  // 列表已经没了, 留着就是一串看不见、也删不掉的残留.
  replyBox.value = {}
  replyLikerBox.value = {}
  replyEdit.value = {}
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

/* ══════════ 举报 ══════════ */

/**
 * 展开/收起一条评论的举报面板.
 *
 * 未登录**不**放行(与点赞、回复都不同): 点赞和回复的读路径是公开的, 举报不是 ——
 * 它是写, 而且服务端那条路也刻意没进免登录清单. 所以这里直接送去登录页, 而不是
 * 让用户填完一整个面板再收一个 401.
 *
 * 已经举报过也**照开着**: 那一下服务端会回 duplicate, 界面用一句提示说清楚就行,
 * 拦在本地反而多一份要维护的状态(它没有真源, 刷新一次就没了).
 */
function toggleReport(review){
  if(!userStore.loggedIn){ router.push('/login'); return }
  const box = reportBox.value[review.id]
  if(box){ box.open = !box.open; return }
  reportBox.value[review.id] = { open: true, reason: '', detail: '', busy: false, done: false }
}

/**
 * 提交举报.
 *
 * `duplicate` 由**服务端**给, 不在本地猜: 幂等路径下服务端什么都没做, 而它照回
 * 200 —— 前端分不清"刚记下了"与"本来就在", 只有那个布尔分得清.
 *
 * 补充说明留空时传 undefined(axios 会把这个键丢掉), 而不是空串: 后端把空白一律
 * 存成 null, 传空串只是让一次没写的填写看起来像写了.
 */
async function submitReport(review){
  const box = reportBox.value[review.id]
  // 与模板上的 :disabled 是两道闸, 理由见那段注释
  if(!box || box.busy) return
  if(!box.reason){ toast('请选择举报理由','warning'); return }
  box.busy = true
  try{
    const res = await reportReview(review.id, {
      reason: box.reason,
      detail: box.detail.trim() || undefined,
    })
    const d = res.data.data || {}
    box.done = true
    box.open = false
    toast(d.duplicate ? '你已经举报过这条评论' : '已收到举报',
      d.duplicate ? 'info' : 'success')
  }catch(e){ toast('举报失败','error') }
  finally{ box.busy = false }
}

/* ══════════ 回复 ══════════ */

/**
 * 展开/收起一条评论下的回复. 未登录**也放行** —— 回复列表是公开的(与评论列表
 * 同一条规矩), 拦在这里的话访客看着「3」却点不开. 写入口在下面几处各自拦.
 */
async function toggleReplies(review){
  const existing = replyBox.value[review.id]
  if(existing){ existing.open = !existing.open; return }
  replyBox.value[review.id] = { open: true, loading: true, list: [] }
  await fetchReplies(review.id)
}

async function fetchReplies(reviewId){
  /* 与 fetchLikers 同一条规矩: 从 ref 里**读回来**再改, 不能拿赋值时的原始引用 ——
     普通对象要经过 reactive 代理才会触发依赖收集 */
  const box = replyBox.value[reviewId]
  if(!box) return
  box.loading = true
  try{
    const res = await getReplies(reviewId)
    box.list = res.data.data || []
  }catch(e){
    // 整个盒子删掉 = 收起来: 不留下一个永远转圈的「加载中…」. 计数还在按钮上,
    // 再点一次就是重试
    delete replyBox.value[reviewId]
    toast('回复加载失败','error')
  }finally{
    box.loading = false
  }
}

/** 发一条回复 */
async function sendReply(review){
  if(!userStore.loggedIn){ router.push('/login'); return }
  if(replyBusy.value[review.id]) return
  const content = (replyDraft.value[review.id] || '').trim()
  // 空回复不必往返一次: 后端 @NotBlank 会回 400, 而"回复失败"读不出是哪里不对
  if(!content){ toast('回复内容不能为空','warning'); return }
  replyBusy.value[review.id] = true
  try{
    const res = await addReply(review.id, content)
    const box = replyBox.value[review.id]
    if(box) box.list.push(res.data.data)
    delete replyDraft.value[review.id]
    // 计数本地 +1 —— 这里**可以**加, 与点赞的计数刻意不同: 点赞是幂等的(再点一次
    // 服务端什么都不做), 本地 +1 会一路错到下次刷新; 而发回复不是幂等的, 服务端
    // 确实新建了一行, 所以 +1 是这次写入的结果, 不是猜的.
    review.replyCount = (review.replyCount || 0) + 1
    toast('已回复','success')
  }catch(e){ toast('回复失败','error') }
  finally{ delete replyBusy.value[review.id] }
}

/** 进入编辑态. 打开发起时的正文, 不是空框 —— 空框看起来像"要重新写一遍" */
function startReplyEdit(reply){
  if(!userStore.loggedIn){ router.push('/login'); return }
  replyEdit.value[reply.id] = reply.content || ''
}

/** 退出编辑态. 草稿一起丢掉: 留着它, 下次点「编辑」看到的是上次没保存的旧字 */
function cancelReplyEdit(reply){
  delete replyEdit.value[reply.id]
}

async function saveReplyEdit(review, reply){
  const content = (replyEdit.value[reply.id] || '').trim()
  if(!content){ toast('回复内容不能为空','warning'); return }
  try{
    const res = await editReply(reply.id, content)
    // 用服务端回的整条替换掉那一行(它带着新的 updatedAt 与 likedByMe)——
    // 只改本地的 content 会让「已编辑」标记永远不出现
    const box = replyBox.value[review.id]
    if(box){
      const i = box.list.findIndex(x => x.id === reply.id)
      if(i >= 0) box.list[i] = res.data.data
    }
    cancelReplyEdit(reply)
    toast('已更新','success')
  }catch(e){ toast('修改失败','error') }
}

async function removeReply(review, reply){
  if(!userStore.loggedIn){ router.push('/login'); return }
  if(!confirm('删除这条回复？')) return
  try{
    await deleteReply(reply.id)
    const box = replyBox.value[review.id]
    if(box) box.list = box.list.filter(x => x.id !== reply.id)
    // 同 sendReply: 删除不是幂等的(第二次会 404), 所以这里减的同一次真删掉的那行
    review.replyCount = Math.max(0, (review.replyCount || 0) - 1)
    toast('已删除','info')
  }catch(e){ toast('删除失败','error') }
}

/** 这条回复改过没有. 服务端不塞布尔, 由两个时间戳比出来(见 ReviewReplyService) ——
 *  判据是严格晚于, 而插入时两者是同一次 now(), 所以刚发的回复不会被标成「已编辑」 */
function isEdited(reply){
  if(!reply.updatedAt || !reply.createdAt) return false
  return new Date(reply.updatedAt) > new Date(reply.createdAt)
}

/** 「删除」按钮摆不摆. 与后端 ReviewReplyService.canDelete 的三支一一对齐 ——
 *  服务端才是判据, 这里只是别把点了会 403 的按钮递给用户 */
function canDeleteReply(review, reply){
  const me = userStore.user
  if(!me) return false
  return Boolean(reply.isOwner || review.isOwner || me.role === 'ADMIN')
}

/** 赞/取消赞一条回复. 形状与理由与 toggleLike 逐条相同 */
async function toggleReplyLike(reply){
  if(!userStore.loggedIn){ router.push('/login'); return }
  if(replyLikeBusy.value[reply.id]) return
  const wanted = !reply.likedByMe
  replyLikeBusy.value[reply.id] = true
  try{
    const res = wanted ? await likeReply(reply.id) : await unlikeReply(reply.id)
    const d = res.data.data || {}
    reply.likedByMe = d.liked ?? wanted
    if(typeof d.likeCount === 'number') reply.likeCount = d.likeCount
    const box = replyLikerBox.value[reply.id]
    if(box){ box.open ? fetchReplyLikers(reply.id) : delete replyLikerBox.value[reply.id] }
  }catch(e){ toast('操作失败','error') }
  finally{ delete replyLikeBusy.value[reply.id] }
}

/** 拉一条回复的点赞人名单. 与 fetchLikers 同构 —— 两处都留着而不是抽一个通用函数:
 *  它们唯一的差别就是中间那一句请求, 而抽出来要传一个闭包进来, 读的时候反而多跳一层 */
async function fetchReplyLikers(replyId){
  const box = replyLikerBox.value[replyId]
  if(!box) return
  box.loading = true
  try{
    const res = await getReplyLikers(replyId)
    const d = res.data.data || {}
    box.names = d.list || []
    box.total = d.total || 0
  }catch(e){
    box.open = false
    toast('加载失败','error')
  }finally{
    box.loading = false
  }
}

/** 展开/收起一条回复的「谁赞了」 */
async function toggleReplyLikers(reply){
  const existing = replyLikerBox.value[reply.id]
  if(existing){ existing.open = !existing.open; return }
  replyLikerBox.value[reply.id] = { open: true, loading: true, total: 0, names: [] }
  await fetchReplyLikers(reply.id)
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
/* 剩下的这个角标压在封面上 → 用 --cover-* 那组, 不跟主题变。 */
/* 这里原本是 800 —— 而正文字体最粗只到 700, 800 是伪粗体合成出来的。
   12px 属于小字号, 一律用真的 700; 20px 以上才换显示体(见下面 .ds-val)。
   (原先还有一条 .d-cover-score, 随那个 ★9.1 角标在 c117 一起删掉了。) */
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
/* 被移除的那条: 一句说明, 中性色. 不用红色 —— 那是"你出错了"的语气, 而用户除了
   "知道了"没有任何可做的动作; 也不必提是谁移除的, 那是管理端的信息(见 getUserReview) */
.mr-removed{ margin:0; font-size:13px; line-height:1.7; color:var(--text-secondary); }

.rate-bars{ display:flex; flex-direction:column; gap:4px; margin-bottom:20px; }
.rate-bar-row{ display:flex; align-items:center; gap:8px; font-size:12px; }
.rbr-label{ width:16px; text-align:center; color:var(--text-muted); font-weight:700; }
.rbr-track{ flex:1; height:5px; background:var(--bg-secondary); border-radius:3px; overflow:hidden; }
.rbr-fill{ height:100%; border-radius:3px; background:var(--primary); transition:width .6s var(--ease); }
.rbr-cnt{ width:22px; text-align:right; color:var(--text-muted); font-size:11px; }

.review-list{ display:flex; flex-direction:column; }
.rv-item{ display:flex; gap:12px; padding:16px 0; border-bottom:1px solid var(--border); }
.rv-avatar{ width:36px; height:36px; border-radius:50%; background:var(--primary); color:var(--primary-foreground); display:flex; align-items:center; justify-content:center; font-weight:700; font-size:14px; flex-shrink:0; overflow:hidden; }
/* 有头像时这一格是图片, 没有时容器自己显示首字母. 裁成圆靠容器上的 overflow
   —— 图片本身多半不是方的, 只给 img 加 border-radius 挡不住 36px 方框外的角 */
.rv-avatar-img{ width:100%; height:100%; object-fit:cover; display:block; }
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
.rv-act.on,
/* 回复区展开着的时候那个按钮也点亮 —— 光靠下面多出来一块, 分不清是"这条评论
   有回复"还是"我看过它了" */
.rv-act.open{ color:var(--text); background:var(--primary-soft); border-color:var(--primary-line); }
.rv-act:disabled{ cursor:default; opacity:.55; }
.rv-act-quiet{ font-weight:500; }
.rv-likers{ display:flex; flex-wrap:wrap; gap:6px; margin-top:8px; }
.rv-liker{
  padding:2px 10px; border-radius:999px; background:var(--tag-bg);
  color:var(--text-secondary); font-size:12px;
}
.rv-likers-hint{ color:var(--text-muted); font-size:12px; }

/* ── 举报面板 ──
   与回复区共用同一根竖线作为层级线索(它是这条评论的下一层), 但底色比回复区重一档:
   回复是内容, 举报是一段要填的表单, 不区分的话满屏都是同一片灰. */
.rv-report{
  margin-top:8px; padding:10px 12px; border-radius:10px;
  background:var(--surface-2, var(--tag-bg)); border:1px solid var(--border);
  display:flex; flex-direction:column; gap:6px;
}
.rv-report-title{ font-size:12px; font-weight:600; color:var(--text-secondary); }
/* 四行单选框排成一列而不是两列: 中文标签长度不一, 两列会参差 */
.rv-report-opt{
  display:flex; align-items:center; gap:6px; font-size:13px;
  color:var(--text-secondary); cursor:pointer;
}
.rv-report-detail{
  width:100%; box-sizing:border-box; resize:vertical; font:inherit; font-size:13px;
  padding:6px 8px; border-radius:8px; border:1px solid var(--border);
  background:var(--surface, transparent); color:var(--text);
}
.rv-report-btns{ display:flex; gap:8px; margin-top:2px; }
.rv-report-send, .rv-report-cancel{
  padding:5px 14px; border-radius:999px; font-size:13px; font-weight:600;
  cursor:pointer; border:1px solid var(--border); font-family:inherit;
}
.rv-report-send{ background:var(--primary); border-color:var(--primary); color:#fff; }
.rv-report-cancel{ background:transparent; color:var(--text-secondary); }
.rv-report-send:disabled, .rv-report-cancel:disabled{ cursor:default; opacity:.55; }

/* ── 回复区 ──
   左边那 4px 的竖线是唯一的层级线索: 回复列表与它上面那条评论共用同一个左边缘,
   没有这根线的话, 一屏里两三条评论各带几条回复就分不清谁是谁楼里的.
   用 --border 而不是更重的色 —— 它是结构, 不是内容. */
.rp-area{ margin-top:12px; padding-left:12px; border-left:2px solid var(--border); }
.rp-hint{ color:var(--text-muted); font-size:12px; padding:4px 0; }
.rp-hint a{ color:var(--primary); font-weight:600; }
.rp-list{ display:flex; flex-direction:column; gap:12px; }
.rp-item{ display:flex; gap:8px; }
/* 比评论的头像小一圈: 回复是评论的下一层, 一样大就分不出主次 */
.rp-avatar{
  width:26px; height:26px; border-radius:50%; background:var(--tag-bg);
  color:var(--text-secondary); display:flex; align-items:center; justify-content:center;
  font-weight:700; font-size:12px; flex-shrink:0; overflow:hidden;
}
/* 理由同 .rv-avatar-img */
.rp-avatar-img{ width:100%; height:100%; object-fit:cover; display:block; }
.rp-body{ flex:1; min-width:0; }
.rp-top{ display:flex; align-items:center; gap:8px; margin-bottom:2px; }
.rp-username{ font-weight:600; font-size:12px; color:var(--text); }
/* 「已编辑」做成一个极轻的标签而不是跟时间挤在一起: 它要一眼看得见(说明这行被
   改过), 又不该抢走时间的顺位 */
.rp-edited{
  padding:1px 6px; border-radius:4px; background:var(--tag-bg);
  color:var(--text-muted); font-size:10px;
}
.rp-time{ font-size:11px; color:var(--text-muted); margin-left:auto; }
/* 回复正文比评论小一号(13 对 14): 同一条评论下面可能挂着十几条回复,
   与评论正文同字号的话整块会糊成一片 */
.rp-text{ font-size:13px; line-height:1.65; color:var(--text-secondary); word-break:break-word; }
.rp-actions{ display:flex; align-items:center; gap:10px; margin-top:4px; margin-left:-6px; }
.rp-act{
  display:inline-flex; align-items:center; gap:4px; padding:2px 6px;
  border-radius:999px; border:1.5px solid transparent; background:transparent;
  color:var(--text-muted); font-size:11px; font-weight:600; cursor:pointer;
  font-family:inherit; font-variant-numeric:tabular-nums;
  transition:color var(--transition), background var(--transition), border-color var(--transition);
}
.rp-act:hover{ color:var(--text); background:var(--primary-soft); }
.rp-act.on{ color:var(--text); background:var(--primary-soft); border-color:var(--primary-line); }
.rp-act:disabled{ cursor:default; opacity:.55; }
.rp-act-quiet{ font-weight:500; }
.rp-likers{ display:flex; flex-wrap:wrap; gap:6px; margin-top:6px; }
.rp-liker{
  padding:1px 8px; border-radius:999px; background:var(--tag-bg);
  color:var(--text-secondary); font-size:11px;
}

.rp-composer{ display:flex; flex-direction:column; align-items:flex-end; gap:6px; margin-top:12px; }
/* width:100% 是必需的: 父级是 align-items:flex-end(为了让右下角那个按钮贴右),
   在这个轴上子元素默认按内容宽度收缩, 而 textarea 的内容宽度约等于 0 */
.rp-input{
  width:100%; padding:8px 10px; border:1.5px solid var(--input-border);
  border-radius:8px; background:var(--input-bg); color:var(--text);
  font-size:13px; resize:vertical; font-family:inherit;
}
.rp-input:focus{ border-color:var(--primary); outline:none; }
.rp-send{
  padding:5px 14px; border-radius:8px; border:none; background:var(--primary);
  color:var(--primary-foreground); font-size:12px; font-weight:700;
  cursor:pointer; font-family:inherit; transition:opacity var(--transition);
}
.rp-send:disabled{ opacity:.55; cursor:default; }
.rp-cancel{
  padding:5px 14px; border-radius:8px; border:1.5px solid var(--border);
  background:transparent; color:var(--text-muted); font-size:12px; font-weight:600;
  cursor:pointer; font-family:inherit;
}
.rp-cancel:hover{ color:var(--text); border-color:var(--primary-line); }
/* 编辑态那一行也是右下角两按钮, 与回复框同一套排法 */
.rp-edit-actions{ display:flex; justify-content:flex-end; gap:6px; margin-top:6px; }

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
