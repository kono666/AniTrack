package com.animetracker.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "anime_tag", indexes = {
    @Index(name = "idx_animetag_tag", columnList = "tag_id"),
    @Index(name = "idx_animetag_anime", columnList = "anime_id")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AnimeTag {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "anime_id", nullable = false)
    private Integer animeId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tag_id", nullable = false)
    private Tag tag;
}
