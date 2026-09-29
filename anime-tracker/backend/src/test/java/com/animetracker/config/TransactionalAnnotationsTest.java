package com.animetracker.config;

import com.animetracker.entity.User;
import com.animetracker.repository.AnimeTagRepository;
import com.animetracker.repository.EpisodeWatchedRepository;
import com.animetracker.service.TagMigrationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAttribute;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 钉住三处「事务注解必须真的生效」的地方(审计 M10 的回归护栏).
 *
 * <p><b>为什么不能只靠「跑一遍看不报错」</b>: 事务注解失效是静默的 —— 少了事务,
 * 批量删除照样删、迁移照样跑完, 只是它们不再原子. 要等到某次中途失败, 才会留下
 * 半截数据; 而 TagMigrationService 的幂等判定是「关联表里已经有行就跳过」,
 * 半截状态会被当成「已经做过」永久留下.
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
     * 两层的判据写在一处, 免得三个用例各写一遍、漏掉其中一个.
     *
     * <p>注解用 {@code getAnnotation} 而不是 {@code AnnotationUtils.findAnnotation}:
     * 这三个方法上的注解都写在方法自己身上, 不需要跨接口/父类去找;
     * 而 findAnnotation 会连**元注解**和同名父方法一起找, 那会把
     * 「注解写在别处、这个方法只是碰巧同名」也算通过.
     */
    private void assertSpringTransactional(Class<?> type, Method method) {
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
                .isEqualTo(TransactionAttribute.PROPAGATION_REQUIRED);
    }
}
