package com.animetracker.service;

import com.animetracker.dto.BangumiDTO.ActorDTO;
import com.animetracker.dto.BangumiDTO.CharacterDTO;
import com.animetracker.dto.BangumiDTO.ImagesDTO;
import com.animetracker.dto.BangumiDTO.PersonDTO;
import com.animetracker.dto.BangumiDTO.RelatedSubjectDTO;
import com.animetracker.entity.SubjectCharacter;
import com.animetracker.entity.SubjectCharacterActor;
import com.animetracker.entity.SubjectRelation;
import com.animetracker.entity.SubjectStaff;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 上游的三个附属数据 → 四张内容表的行. <b>纯函数, 不碰 IO</b>.
 *
 * <p>它存在的主要理由是下面那件必须做对的事, 而它一旦做错, 失效方式是<b>整块永远空着</b>:
 *
 * <h2>截断: 一行超长会打死整批</h2>
 *
 * <p>一次完整回源是<b>一个事务</b>: 先删旧行, 再插新行, 最后写 marker。任何一行超过列宽
 * 都会让整个事务回滚 —— 于是 marker 不写、旧行被删掉这件事也一并回滚(这是好事),
 * 但上游下次给的还是同一批数据、还是同一行超长, 于是<b>再失败一次</b>。结果就是这个条目
 * 的"角色与制作人员"永远是空的, 而日志里只有一条语法正确的 SQL 错误, 与用户看到的
 * 现象隔了好几层。
 *
 * <p>所以宁可截断也不让它失败: 列宽取的是实测最长值的几倍(实测角色名最长 28 字、
 * 关联条目名最长 58 字), 截断是给"上游哪天出现一个超长的名字"准备的, 不是常态。
 * 这一条有单测钉着(500 字进去 200 字出来)。
 *
 * <h2>图片选哪一档: 偏好顺序而不是写死一档</h2>
 *
 * <p>三个接口给的图片变体不一样(characters/persons <b>没有 common</b>, subjects 反过来
 * <b>只有 large 能过白名单</b> —— 实测见 {@code BangumiDTO.ImagesDTO})。所以每一块给一个
 * <b>偏好顺序</b>, 取第一个非空的: 某一档缺失时退到下一档, 而不是留一个空图。
 *
 * <p>⚠️ 这里选的是<b>上游地址</b>, 不是代理地址 —— 过 {@code CoverImages.proxied()} 是
 * 读的时候做的事, 与 {@code anime.cover_url} 一致。理由写在 {@code SubjectCharacter.image}
 * 上: 存代理地址等于把当前的白名单规则冻结进数据里。
 */
@Component
public class SubjectExtrasMapper {

    /**
     * 名字列的最大长度. 实测最长 58(关联条目的原名), 给到 200 是几倍余量 ——
     * 这个数只在与 {@code V17} 的 DDL 不一致时才有意义, 两边必须一起改。
     */
    static final int NAME_MAX = 200;

    /** 职务/定位列的最大长度. 实测最长 8(人员职务), 给到 32 */
    static final int RELATION_MAX = 32;

    /** 图片地址列的最大长度. 装得下 {@code /api/img?url=…} 那种百分号编码后的长地址 */
    static final int IMAGE_MAX = 500;

    /**
     * 角色与声优的图片偏好. grid 与 large 的通过率相同(实测 92.9%)而 grid 小得多,
     * 所以先 grid。
     */
    private static final String[] CHARACTER_IMAGE_ORDER =
            {"grid", "large", "common", "medium", "small"};

    /** 制作人员同角色那一侧(实测 grid 与 large 并列 60.9%, 其余档位一个都不通过) */
    private static final String[] STAFF_IMAGE_ORDER =
            {"grid", "large", "common", "medium", "small"};

    /**
     * 关联条目的图片偏好 —— <b>large 必须排第一</b>。
     *
     * <p>实测这一个接口的 grid/common/medium/small <b>全部</b>是 {@code /r/N/pic/…} 那种
     * 形式(0% 通过白名单), 只有 large 是 {@code /pic/…}(100%)。排错顺序的后果不是报错,
     * 而是每张卡都退回直连上游 —— 能用, 但白名单形同虚设, 而且没有任何症状。
     */
    private static final String[] RELATION_IMAGE_ORDER =
            {"large", "common", "grid", "medium", "small"};

    /** 一次角色回源产生的两批行. 两批来自同一个响应, 必须一起写 */
    public record CharacterRows(List<SubjectCharacter> characters,
                                List<SubjectCharacterActor> actors) {}

    /** 角色 + 声优 */
    public CharacterRows toCharacters(Integer subjectId, List<CharacterDTO> src) {
        List<SubjectCharacter> characters = new ArrayList<>();
        List<SubjectCharacterActor> actors = new ArrayList<>();

        for (int i = 0; i < src.size(); i++) {
            CharacterDTO dto = src.get(i);
            if (dto == null || dto.getId() == null) {
                continue;   // 没有 id 的行落下去也没法用, 而 id 是这几张表的全部意义
            }
            characters.add(SubjectCharacter.builder()
                    .subjectId(subjectId)
                    .characterId(dto.getId())
                    .name(text(dto.getName()))
                    .relation(cap(dto.getRelation(), RELATION_MAX))
                    .image(cap(pickImage(dto.getImages(), CHARACTER_IMAGE_ORDER), IMAGE_MAX))
                    .sortOrder(i)
                    .build());

            List<ActorDTO> list = dto.getActors();
            if (list == null) {
                continue;
            }
            for (ActorDTO a : list) {
                if (a == null || a.getId() == null) {
                    continue;
                }
                // sortOrder 用**整个条目**里的序号而不是角色内部的序号: 读的时候要按它
                // 还原上游给的顺序, 而那个顺序是跨角色的一整条序列
                actors.add(SubjectCharacterActor.builder()
                        .subjectId(subjectId)
                        .characterId(dto.getId())
                        .actorId(a.getId())
                        .name(text(a.getName()))
                        .image(cap(pickImage(a.getImages(), CHARACTER_IMAGE_ORDER), IMAGE_MAX))
                        .sortOrder(actors.size())
                        .build());
            }
        }
        return new CharacterRows(characters, actors);
    }

    /** 制作人员 */
    public List<SubjectStaff> toStaff(Integer subjectId, List<PersonDTO> src) {
        List<SubjectStaff> rows = new ArrayList<>();
        for (int i = 0; i < src.size(); i++) {
            PersonDTO dto = src.get(i);
            if (dto == null || dto.getId() == null) {
                continue;
            }
            rows.add(SubjectStaff.builder()
                    .subjectId(subjectId)
                    .personId(dto.getId())
                    .name(text(dto.getName()))
                    .relation(cap(dto.getRelation(), RELATION_MAX))
                    .image(cap(pickImage(dto.getImages(), STAFF_IMAGE_ORDER), IMAGE_MAX))
                    .sortOrder(i)
                    .build());
        }
        return rows;
    }

    /**
     * 关联条目.
     *
     * <p>⚠️ {@code relatedId} 可能<b>与 {@code subjectId} 相同</b> —— 实测上游的关联列表里
     * 不会出现自己, 所以这一条没有额外的过滤; 万一哪天出现了, 前端那一格会多出一张指向
     * 当前页面的卡, 而那不是数据损坏。刻意不过滤, 是因为"上游会不会返回自己"这件事
     * 不该由我们猜, 而过滤掉一行会让行数与上游对不上。
     */
    public List<SubjectRelation> toRelations(Integer subjectId, List<RelatedSubjectDTO> src) {
        List<SubjectRelation> rows = new ArrayList<>();
        for (int i = 0; i < src.size(); i++) {
            RelatedSubjectDTO dto = src.get(i);
            if (dto == null || dto.getId() == null) {
                continue;
            }
            rows.add(SubjectRelation.builder()
                    .subjectId(subjectId)
                    .relatedId(dto.getId())
                    .name(cap(dto.getName(), NAME_MAX))
                    .nameCn(cap(dto.getNameCn(), NAME_MAX))
                    .relation(cap(dto.getRelation(), RELATION_MAX))
                    .image(cap(pickImage(dto.getImages(), RELATION_IMAGE_ORDER), IMAGE_MAX))
                    .sortOrder(i)
                    .build());
        }
        return rows;
    }

    // ── 纯函数 ────────────────────────────────────────

    /** 非空的那一列用这个: null 变成空串, 好落进 NOT NULL 的列 */
    static String text(String s) {
        return s == null ? "" : cap(s, NAME_MAX);
    }

    /**
     * 截到 {@code max} 个字符; null 原样返回(列可空时用这个).
     *
     * <p>⚠️ 末尾那个代理对(代理项)的检查不是多余的: 直接 {@code substring} 有可能把
     * 一个字符切成半个, 而半个代理项<b>编码不成 UTF-8</b> —— 写库时抛异常, 于是又回到
     * "整批回滚、这块永远空着"那条路上去。多留一个字符的位置、或者少切一个, 两行就够,
     * 而这种名字(带 emoji 或生僻字的角色名)在上游是真实存在的形状。
     */
    static String cap(String s, int max) {
        if (s == null) {
            return null;
        }
        if (s.length() <= max) {
            return s;
        }
        // 第 max 个字符(下标 max-1)是高位代理项的话, 把它一起留给截断之后的部分
        int end = Character.isHighSurrogate(s.charAt(max - 1)) ? max - 1 : max;
        return s.substring(0, end);
    }

    /** 按偏好顺序取第一个非空的变体; 一个都没有就返回 null */
    static String pickImage(ImagesDTO images, String[] order) {
        if (images == null) {
            return null;
        }
        for (String variant : order) {
            String url = switch (variant) {
                case "large" -> images.getLarge();
                case "common" -> images.getCommon();
                case "medium" -> images.getMedium();
                case "grid" -> images.getGrid();
                case "small" -> images.getSmall();
                default -> null;
            };
            if (url != null && !url.isBlank()) {
                return url;
            }
        }
        return null;
    }
}
