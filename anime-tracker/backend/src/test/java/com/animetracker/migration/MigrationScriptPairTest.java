package com.animetracker.migration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 守住「H2 与 PostgreSQL 两套迁移脚本必须成对」这条约定。
 *
 * <p>为什么需要这个测试：表结构脚本按方言写了两份（见 db/migration/h2 与 db/migration/postgres），
 * 每加一列就得改两个地方。少改一个不会报错 —— 因为两个环境各自只加载自己那一套，
 * 谁也不会发现另一套少了东西，直到某天有人在生产上部署、发现表里根本没有那一列。
 * 这正是这套迁移机制想要消灭的那类问题，所以用测试把它钉住。
 *
 * <p>顺便钉住两件事：
 * <ul>
 *   <li>文件名必须符合 Flyway 的命名规则（版本号 + 双下划线 + 小写下划线描述），
 *       写错名字的脚本会被 Flyway 直接忽略或报错，而错误信息不一定好懂；</li>
 *   <li>同一个目录里版本号不能重复 —— 重复时 Flyway 报的是
 *       "Found more than one migration with version N"，不看目录很难反应过来
 *       （常见诱因是删/改了脚本名但没 mvn clean，target/classes 里还留着旧文件）。</li>
 * </ul>
 *
 * <p>读的是源码目录而不是 classpath：classpath 上会混进上一次构建的残留文件，
 * 那样测试会因为构建产物而红，反而掩盖了真正的问题。
 */
class MigrationScriptPairTest {

    /** 迁移脚本的根目录（Maven 跑测试时的工作目录就是模块根，也就是 backend/）。 */
    private static final Path MIGRATION_ROOT = Path.of("src", "main", "resources", "db", "migration");

    private static final List<String> DIALECTS = List.of("h2", "postgres");

    /** Flyway 的命名规则：V + 数字 + 双下划线 + 小写下划线描述 + .sql */
    private static final Pattern FILE_NAME = Pattern.compile("^V(\\d+)__([a-z0-9]+(?:_[a-z0-9]+)*)\\.sql$");

    @Test
    @DisplayName("两个方言目录的脚本必须一一对应（少一个就是漏改，多一个就是写错地方）")
    void dialectsMustHaveIdenticalScriptNames() throws IOException {
        Map<String, Set<String>> byDialect = DIALECTS.stream()
                .collect(Collectors.toMap(d -> d, d -> fileNames(d), (a, b) -> a, java.util.LinkedHashMap::new));

        byDialect.forEach((dialect, names) ->
                assertThat(names)
                        .as("db/migration/%s 里没有任何脚本 —— 是不是挪了目录？", dialect)
                        .isNotEmpty());

        for (String dialect : DIALECTS) {
            List<String> others = DIALECTS.stream().filter(d -> !d.equals(dialect)).toList();
            for (String other : others) {
                assertThat(byDialect.get(dialect))
                        .as("db/migration/%s 与 db/migration/%s 的脚本文件名必须完全相同（%s 只加载自己那一套，"
                                + "少的那一份不会被任何环境发现）", dialect, other, dialect)
                        .isEqualTo(byDialect.get(other));
            }
        }
    }

    @Test
    @DisplayName("文件名要符合 Flyway 命名规则，且同目录内版本号不重复")
    void fileNamesAndVersionsAreWellFormed() throws IOException {
        for (String dialect : DIALECTS) {
            Set<Integer> versions = new TreeSet<>();
            for (String name : fileNames(dialect)) {
                Matcher matcher = FILE_NAME.matcher(name);
                assertThat(matcher.matches())
                        .as("db/migration/%s/%s 不符合 Flyway 命名规则: V<数字>__<小写下划线描述>.sql", dialect, name)
                        .isTrue();
                int version = Integer.parseInt(matcher.group(1));
                assertThat(versions.add(version))
                        .as("db/migration/%s 里版本号 %d 出现了不止一次", dialect, version)
                        .isTrue();
            }
            assertThat(versions)
                    .as("db/migration/%s 的版本号应当从 1 开始连续（缺号通常意味着脚本被删掉或漏提交）", dialect)
                    .containsExactlyElementsOf(java.util.stream.IntStream
                            .rangeClosed(1, versions.size()).boxed().toList());
        }
    }

    @Test
    @DisplayName("脚本只能放在方言子目录里 —— 根目录下的脚本会被 Flyway 静默忽略")
    void noScriptsAtMigrationRoot() throws IOException {
        try (Stream<Path> files = Files.list(MIGRATION_ROOT)) {
            List<String> stray = files
                    .filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .sorted(Comparator.naturalOrder())
                    .toList();
            assertThat(stray)
                    .as("db/migration 根目录下不该有文件：每个 profile 只加载自己的方言子目录，"
                            + "放在这里既不会被 H2 环境执行、也不会被 PG 环境执行")
                    .isEmpty();
        }
    }

    private static Set<String> fileNames(String dialect) {
        Path dir = MIGRATION_ROOT.resolve(dialect);
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .collect(Collectors.toCollection(TreeSet::new));
        } catch (IOException e) {
            throw new IllegalStateException("读取 " + dir + " 失败", e);
        }
    }
}
