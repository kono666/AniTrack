package com.animetracker.config;

import com.animetracker.entity.User;
import com.animetracker.repository.AnimeTagRepository;
import com.animetracker.repository.EpisodeWatchedRepository;
import com.animetracker.service.AdminService;
import com.animetracker.service.IsolatedInsert;
import com.animetracker.service.SubjectExtrasMapper;
import com.animetracker.service.SubjectExtrasService;
import com.animetracker.service.SubjectExtrasWriter;
import com.animetracker.service.TagMigrationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAttribute;

import java.lang.reflect.Method;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 钉住几处「事务注解必须真的生效」的地方(审计 M10 的回归护栏).
 *
 * <p><b>为什么不能只靠「跑一遍看不报错」</b>: 事务注解失效是静默的 —— 少了事务,
 * 批量删除照样删、迁移照样跑完, 只是它们不再原子. 要等到某次中途失败, 才会留下
 * 半截数据; 而 TagMigrationService 的幂等判定是「关联表里已经有行就跳过」,
 * 半截状态会被当成「已经做过」永久留下.
 *
 * <p>最后两条与前面三条的方向相反: 它们钉的是**注解不在这里**(以及传播行为是这个值).
 * 一个「善意地」加上的类级 {@code @Transactional} 不会让任何用例变红, 却会给 Agent
 * 工具那条链多套一层事务、并让「工具失败了网页接口还能自愈」这条性质消失 —— 与把
 * 一个必需的事务弄丢是同一类错误的两个方向, 都只能靠断言挡.
 *
 * <p>判据分两层, 缺一不可:
 * <ol>
 *   <li><b>注解本身是 Spring 那份</b>. 这一层看着多余, 其实是必要的:
 *       Spring 也解析 {@code jakarta.transaction.Transactional}
 *       (由 JtaTransactionAnnotationParser 承担), 所以只验第二层的话,
 *       把注解换回 jakarta 那份、甚至两份混用, 测试照样绿;</li>
 *   <li><b>Spring 的事务基础设施能从它解析出属性</b>. 这一层验的是「能被代理调用到」
 *       这个前提: 非 public 的方法解析结果直接是 null(publicMethodsOnly),
 *       这就是「把逻辑挪进 private 方法再自调用, 事务会静默消失」的机制本身.</li>
 * </ol>
 *
 * <p>不启动 Spring 上下文: 这里问的是注解与解析规则, 与 bean 装配无关,
 * 而起一个上下文只为反射两个方法, 会让这条护栏跟着别处的改动一起变红.
 */
class TransactionalAnnotationsTest {

    /** 用的就是 Spring 事务代理内部那一个解析器(默认 publicMethodsOnly) */
    private final AnnotationTransactionAttributeSource source = new AnnotationTransactionAttributeSource();

    @Test
    @DisplayName("TagMigrationService.run 上挂着 Spring 的 @Transactional, 且能被事务基础设施解析")
    void tagMigrationRunIsTransactional() throws Exception {
        assertSpringTransactional(TagMigrationService.class,
                TagMigrationService.class.getMethod("run", String[].class));
    }

    @Test
    @DisplayName("AnimeTagRepository.deleteByAnimeId 上挂着 Spring 的 @Transactional")
    void animeTagDeleteIsTransactional() throws Exception {
        assertSpringTransactional(AnimeTagRepository.class,
                AnimeTagRepository.class.getMethod("deleteByAnimeId", Integer.class));
    }

    @Test
    @DisplayName("EpisodeWatchedRepository 的按集删除上挂着 Spring 的 @Transactional")
    void episodeWatchedDeleteIsTransactional() throws Exception {
        assertSpringTransactional(EpisodeWatchedRepository.class,
                EpisodeWatchedRepository.class.getMethod(
                        "deleteByUserAndAnimeIdAndEpisodeNum",
                        User.class, Integer.class, Integer.class));
    }

    /**
     * 对照: 非 public 的方法, 即使标了 @Transactional, 解析结果也是 null.
     *
     * <p>这不是在测 Spring, 而是把上面那条注解检查的**判据**自证一遍 ——
     * 否则「解析出来非 null」可能只是因为解析器对什么方法都返回非 null,
     * 那三条用例就成了摆设. 它同时是 TagMigrationService 上那段注释
     * (「挪进 private 方法再自调用, 事务会静默消失」)的可执行版本.
     */
    @Test
    @DisplayName("对照: 非 public 方法上的 @Transactional 解析不出属性(事务会静默消失)")
    void nonPublicMethodYieldsNoTransactionAttribute() throws Exception {
        Method priv = TransactionalAnnotationsTest.class.getDeclaredMethod("privateTransactional");
        assertThat(priv.getAnnotation(Transactional.class)).isNotNull();

        assertThat(source.getTransactionAttribute(priv, TransactionalAnnotationsTest.class)).isNull();
    }

    @Transactional
    private void privateTransactional() {
        // 只为上面那条对照存在, 不会被调用
    }

    /**
     * {@link IsolatedInsert#attempt} 是**第四个**该被钉住的事务入口, 而且是唯一一个
     * 传播行为不是 REQUIRED 的.
     *
     * <p>改成 REQUIRED 的后果很具体: Agent 工具那条链上外层已经有一个
     * {@code ToolTransactionRunner} 开的事务, REQUIRED 会**加入**它 —— 于是唯一约束冲突
     * 把外层事务标记成 rollback-only, 调用方即使当场捕获异常也提交不了, 后面所有补救
     * (重查、改写)全部作废. 那正是这个类存在的全部理由.
     *
     * <p>同时钉住「它还得是个能被代理到的 public 方法」: 挪进 private 或者改成
     * 自调用, 注解就白标了, 而 {@code attempt} 会退化成"把回调跑一遍".
     */
    @Test
    @DisplayName("IsolatedInsert.attempt 上挂着 Spring 的 @Transactional, 传播行为是 REQUIRES_NEW")
    void isolatedInsertAttemptIsRequiresNew() throws Exception {
        assertSpringTransactional(IsolatedInsert.class,
                IsolatedInsert.class.getMethod("attempt", Supplier.class),
                TransactionAttribute.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * {@link AdminService} 类上**没有** {@code @Transactional} —— 这是刻意的.
     *
     * <p>加上去之后: Agent 工具那条链上会多出一层嵌套; 而且四个破坏性动作里
     * 「动作 + 记账」那两笔写会被并进一个**外面开的**事务, 一旦记账失败, 回滚的
     * 范围就不止这个动作了. 需要「多个写同生共死」的地方一律用
     * {@link IsolatedInsert#attempt} 显式圈出来, 那条边界是看得见的.
     */
    @Test
    @DisplayName("AdminService 类上没有 @Transactional —— 加上去会给 Agent 工具那条链多套一层")
    void adminServiceHasNoClassLevelTransaction() {
        assertThat(AdminService.class.getAnnotation(Transactional.class))
                .as("四个破坏性动作各自用 IsolatedInsert.attempt 圈出边界, 类级注解会把这层边界搅乱")
                .isNull();
        assertThat(AdminService.class.getAnnotation(jakarta.transaction.Transactional.class))
                .as("两套事务注解不要并存")
                .isNull();
    }

    /**
     * 附属数据的三个落库方法各挂一个 Spring 的 {@code @Transactional}, 传播行为是默认的
     * {@code REQUIRED}。
     *
     * <p>它们守住的是"先删后插 + 写 marker"三件事同生共死。丢掉事务的后果<b>在正常路径上
     * 一条用例都不会红</b>: 数据照样写进去, 只是不再原子 —— 要到某次中途失败才会留下
     * "内容被删了、新的没插进去、marker 还说取过"的状态, 而那块内容从此永远空着。
     *
     * <p>⚠️ 这三条<b>只证明注解在、能被解析</b>, 证明不了"运行时真的开了事务" ——
     * 那一条只能靠 {@code SubjectExtrasWriterTest} 里真库上的回滚断言。两者缺一不可:
     * 那边证明行为, 这边防的是"某次重构把注解删了/把方法挪走了, 而那边恰好没跑到"。
     *
     * <p>顺带钉住"这三个方法必须留在这个类里且是 public": {@code SubjectExtrasService}
     * 调用它们走的是跨 bean 的代理调用, 一旦有人把它们搬回 service 并改成 {@code this.}
     * 自调用, 事务就静默消失了 —— 那正是这个类存在的理由。
     */
    @Test
    @DisplayName("SubjectExtrasWriter 的三个落库方法都是 Spring @Transactional 且能被解析")
    void subjectExtrasWriterMethodsAreTransactional() throws Exception {
        assertSpringTransactional(SubjectExtrasWriter.class,
                SubjectExtrasWriter.class.getMethod("replaceCharacters",
                        Integer.class, SubjectExtrasMapper.CharacterRows.class));
        assertSpringTransactional(SubjectExtrasWriter.class,
                SubjectExtrasWriter.class.getMethod("replaceStaff", Integer.class, List.class));
        assertSpringTransactional(SubjectExtrasWriter.class,
                SubjectExtrasWriter.class.getMethod("replaceRelations", Integer.class, List.class));
    }

    /**
     * {@link SubjectExtrasService} 类上**没有** {@code @Transactional} —— 这是刻意的,
     * 而且是这一版最容易被"顺手改好"的一处。
     *
     * <p>{@code AnimeService.getAnimeDetail}/{@code getEpisodes} 都把 HTTP 放在事务里,
     * 而这里刻意不: 公开端点上一次请求会占住一个数据库连接最长 30–60 秒, 上游一慢,
     * 全站共用的连接池先被占满 —— 一个第三方服务的抖动会顺着这条路径放大成整个站点
     * 不可用。所以取数(本类, 无事务)与落库({@link SubjectExtrasWriter}, 有事务)是分开的。
     *
     * <p>加上类级注解不会让任何用例变红, 只会把上面那条性质悄悄换掉。所以只能靠断言挡。
     */
    @Test
    @DisplayName("SubjectExtrasService 类上没有 @Transactional —— HTTP 必须在事务外")
    void subjectExtrasServiceHasNoClassLevelTransaction() {
        assertThat(SubjectExtrasService.class.getAnnotation(Transactional.class))
                .as("类级事务会把回源那段 HTTP 一起圈进来: 上游一慢就占住全站共用的连接池")
                .isNull();
        assertThat(SubjectExtrasService.class.getAnnotation(jakarta.transaction.Transactional.class))
                .as("两套事务注解不要并存")
                .isNull();
    }

    private void assertSpringTransactional(Class<?> type, Method method) {
        assertSpringTransactional(type, method, TransactionAttribute.PROPAGATION_REQUIRED);
    }

    /**
     * 两层的判据写在一处, 免得几个用例各写一遍、漏掉其中一个.
     *
     * <p>注解用 {@code getAnnotation} 而不是 {@code AnnotationUtils.findAnnotation}:
     * 这几个方法上的注解都写在方法自己身上, 不需要跨接口/父类去找;
     * 而 findAnnotation 会连**元注解**和同名父方法一起找, 那会把
     * 「注解写在别处、这个方法只是碰巧同名」也算通过.
     */
    private void assertSpringTransactional(Class<?> type, Method method, int propagation) {
        assertThat(method.getAnnotation(Transactional.class))
                .as("%s 上必须是 Spring 那份 @Transactional", method.getName())
                .isNotNull();
        assertThat(method.getAnnotation(jakarta.transaction.Transactional.class))
                .as("两套事务注解不要并存: %s", method.getName())
                .isNull();

        TransactionAttribute attr = source.getTransactionAttribute(method, type);
        assertThat(attr)
                .as("%s 的 @Transactional 必须能被事务基础设施解析出来(非 public 就是这里为 null)",
                        method.getName())
                .isNotNull();
        assertThat(attr.getPropagationBehavior())
                .as("%s 的传播行为", method.getName())
                .isEqualTo(propagation);
    }
}
