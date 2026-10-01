-- ============================================================================
--  AniTrack · V10 记录「最后一次改密码的时刻」（PostgreSQL）
--
--  背景、取舍与 H2 那份逐条相同，见 h2/V10__add_password_changed_at.sql 的头部注释。
--  一句话版本：补上改密码的能力之后，旧 token 必须立刻作废，而 JWT 是无状态的、
--  本仓的 token 又不带 jti —— 所以要在库里留一个「这张 token 是不是改密之前签发的」
--  的判断依据。可空、无默认，NULL 读作「从未改过密码」，存量用户一律放行。
--
--  两份脚本的语句逐字相同（用例守着这一点）：ADD COLUMN IF NOT EXISTS 与 TIMESTAMP(6)
--  都是 H2 2.2 与 PostgreSQL 16 双方都认的写法，所以这一版不需要开例外，
--  与 V4/V5/V7/V8/V9 同规矩。
-- ============================================================================

ALTER TABLE "user" ADD COLUMN IF NOT EXISTS password_changed_at TIMESTAMP(6);
