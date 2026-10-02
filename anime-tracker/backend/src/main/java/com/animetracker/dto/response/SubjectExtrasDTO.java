package com.animetracker.dto.response;

import com.animetracker.entity.SubjectCharacter;
import com.animetracker.entity.SubjectCharacterActor;
import com.animetracker.entity.SubjectRelation;
import com.animetracker.entity.SubjectStaff;
import com.animetracker.util.CoverImages;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 附属数据(角色 / 制作人员 / 关联条目)的响应 DTO.
 *
 * <p>四个都做成"平铺的卡片", 而不是复用 {@link AnimeDTO} 那种 {@code images:{large,common,
 * medium}} 的嵌套结构: 那边的三档是为了兼容 Bangumi 的返回形状, 而这三个接口最终只选出
 * <b>一张</b>图(选法见 {@code SubjectExtrasMapper} 里的偏好顺序)。多发两个用不上的字段,
 * 就是让读的人再想一次"这三个是不是不同的图"。
 *
 * <p><b>{@code image} 已经过 {@code CoverImages.proxied()}</b> —— 白名单内的换成
 * {@code /api/img?url=…} 由我们代取, 其余(以及 null)原样返回。所以前端<b>不拼 URL、
 * 也不判白名单</b>: 这套逻辑只允许有一处, 理由见 {@code CoverImages} 的类注释。
 */
public final class SubjectExtrasDTO {

    private SubjectExtrasDTO() {
    }

    /** 一个角色, 带着它的声优 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CharacterDTO {
        /** 上游的角色 id */
        private Integer id;
        private String name;
        /** 主角 / 配角 / 闲角 / 旁白. 上游可能不给 */
        private String relation;
        private String image;
        /** 可以为空 —— 实测 128 条角色里 63 条没有声优, 那不是异常 */
        private List<ActorDTO> actors;

        public static CharacterDTO from(SubjectCharacter c, List<SubjectCharacterActor> actors) {
            return CharacterDTO.builder()
                    .id(c.getCharacterId())
                    .name(c.getName())
                    .relation(c.getRelation())
                    .image(CoverImages.proxied(c.getImage()))
                    .actors(actors.stream().map(ActorDTO::from).toList())
                    .build();
        }
    }

    /** 一个声优. 上游的 actor 元素里没有职务, 所以这里也没有那个字段 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ActorDTO {
        private Integer id;
        private String name;
        private String image;

        public static ActorDTO from(SubjectCharacterActor a) {
            return ActorDTO.builder()
                    .id(a.getActorId())
                    .name(a.getName())
                    .image(CoverImages.proxied(a.getImage()))
                    .build();
        }
    }

    /** 一位制作人员 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StaffDTO {
        /** 上游的人物 id */
        private Integer id;
        private String name;
        /** 原画 / 作画监督 / 演出 … */
        private String relation;
        private String image;

        public static StaffDTO from(SubjectStaff s) {
            return StaffDTO.builder()
                    .id(s.getPersonId())
                    .name(s.getName())
                    .relation(s.getRelation())
                    .image(CoverImages.proxied(s.getImage()))
                    .build();
        }
    }

    /**
     * 一个关联条目.
     *
     * <p>{@code name} 与 {@code nameCn} <b>两个都发</b>, 让前端用
     * {@code nameCn || name} 挑一个显示 —— 与 {@link AnimeDTO} 同一套约定。
     * 不在这里先挑好, 是因为"没有中文名"与"中文名是空串"在服务端挑完就分不出来了,
     * 而两者在界面上都是"显示原名"这一种结果, 本就不需要分 —— 但那是**前端**的排版决定,
     * 服务端替它做等于把排版规则埋在 API 里。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RelationDTO {
        private Integer id;
        private String name;
        private String nameCn;
        /** 前传 / 续集 / 剧场版 / 游戏 / 画集 … */
        private String relation;
        private String image;

        public static RelationDTO from(SubjectRelation r) {
            return RelationDTO.builder()
                    .id(r.getRelatedId())
                    .name(r.getName())
                    .nameCn(r.getNameCn())
                    .relation(r.getRelation())
                    .image(CoverImages.proxied(r.getImage()))
                    .build();
        }
    }
}
