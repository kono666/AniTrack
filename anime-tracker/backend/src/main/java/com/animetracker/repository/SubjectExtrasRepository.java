package com.animetracker.repository;

import com.animetracker.entity.SubjectExtras;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 附属数据的「取过没有」账本. 只有读写一行, 没有任何按条件查询.
 *
 * <p>写入口只有一个: {@link com.animetracker.service.SubjectExtrasWriter} ——
 * marker 必须与内容在<b>同一个事务</b>里落下去, 所以这里刻意不提供"单独改一列"的方法。
 * 分开写的话, 中途失败会留下"marker 说有、内容表是空的"这种状态, 而它的表现是
 * 详情页上那一块<b>永远空着</b>(判据认为已经取过了), 且没有任何地方会报错。
 */
public interface SubjectExtrasRepository extends JpaRepository<SubjectExtras, Integer> {
}
