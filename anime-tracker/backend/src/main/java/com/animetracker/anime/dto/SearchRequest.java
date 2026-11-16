package com.animetracker.anime.dto;

import jakarta.validation.constraints.*;
import lombok.Data;

@Data
public class SearchRequest {
    private String keyword;
    private String type;            // 1=book, 2=anime, 3=music, 4=game, 6=real
    private Integer page = 1;
    @Min(value = 1, message = "每页条数不能小于 1")
    @Max(value = 50, message = "每页条数不能超过 50")
    private Integer limit = 20;
}
