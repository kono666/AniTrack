package com.animetracker.dto.response;

import com.animetracker.entity.Episode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 剧集响应 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EpisodeDTO {
    private Long id;
    private Integer sort;     // 第几集
    private String name;      // 日文标题
    private String nameCn;    // 中文标题
    private String airdate;
    private String duration;

    public static EpisodeDTO from(Episode ep) {
        return EpisodeDTO.builder()
                .id(ep.getId())
                .sort(ep.getEpisodeNum())
                .name(ep.getTitle())        // Episode实体存的是中文名
                .nameCn(ep.getTitle())
                .airdate(ep.getAirdate())
                .duration(ep.getDuration())
                .build();
    }
}
