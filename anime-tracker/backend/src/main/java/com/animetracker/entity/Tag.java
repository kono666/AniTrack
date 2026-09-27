package com.animetracker.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "tag", indexes = {
    @Index(name = "idx_tag_name", columnList = "name", unique = true)
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Tag {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100, unique = true)
    private String name; // 中文标签名, 如 "异世界" "冒险"
}
