package com.animetracker.repository;

import com.animetracker.entity.SubjectCharacter;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 角色的读写. 两条查询, 对应"完整回源时先删后插"这一件事的两半.
 *
 * <p><b>两个方法都带 {@code ORDER BY sort_order} / 都按 {@code subject_id} 过滤</b>,
 * 走的是 V17 里那条 {@code idx_subject_character_subject}. 排序刻意放在 SQL 里而不是
 * 让调用方 {@code List.sort}: 落库是"先删后插", 行的物理顺序已经与上游无关了,
 * 而"主角不该排在闲角后面"这件事只在读的时候补得回来 —— 补在 SQL 里只有一处,
 * 补在调用方就要每个调用点各记得一次。
 */
public interface SubjectCharacterRepository extends JpaRepository<SubjectCharacter, Long> {

    List<SubjectCharacter> findBySubjectIdOrderBySortOrderAsc(Integer subjectId);

    /**
     * 清掉这个条目的全部角色.
     *
     * <p>用一条批量 DELETE 而不是"先查出来再挨个 delete": 后者会把整个列表拉进持久化
     * 上下文, 128 个角色就是 128 条 DELETE + 一次 SELECT, 而这里根本不需要看那些行。
     *
     * <p>⚠️ 它<b>必须在与插入同一个事务里</b>调用 —— 先删后插的中间态是"这个条目一个
     * 角色都没有", 那个状态一旦被别的请求读走(或者自己提交了), 用户看到的是
     * 一块凭空消失的内容。这一条由 {@code SubjectExtrasWriter} 的 {@code @Transactional}
     * 保证, 见那里的注释。
     */
    @Modifying
    @Transactional
    @Query("DELETE FROM SubjectCharacter c WHERE c.subjectId = :subjectId")
    int deleteBySubjectId(@Param("subjectId") Integer subjectId);
}
