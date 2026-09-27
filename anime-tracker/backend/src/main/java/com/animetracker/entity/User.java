package com.animetracker.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "`user`")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 50)
    private String username;

    @Column(nullable = false)
    private String password;

    @Column(length = 100)
    private String email;

    @Column(length = 255)
    private String avatar;

    /** 角色: USER / ADMIN */
    @Column(nullable = false, length = 10)
    @Builder.Default
    private String role = "USER";

    /** 账号状态: ACTIVE / DISABLED */
    @Column(nullable = false, length = 10)
    @Builder.Default
    private String status = "ACTIVE";

    @Column(updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
