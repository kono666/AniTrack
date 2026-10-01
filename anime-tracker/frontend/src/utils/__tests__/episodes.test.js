import { describe, it, expect } from 'vitest'
import { effectiveEpisodes } from '../episodes'

/**
 * 分母的取值口径.
 *
 * 这一组钉的是**一个真实根因**: `anime.total_episodes` 来自 Bangumi 的 `total_episodes`,
 * 而上游对绝大多数条目就是填 0(2026-10-02 实测 29379 条里 29322 条是 0)。改前三个页面
 * 各自拿它当真值判断, 于是进度条对 99.8% 的番整根不渲染、详情页的封顶整个失效。
 *
 * 顺序(声明优先)是这里最值钱的一条: 反过来的话, 一个正在连载的番本地只收到已播的 8 集,
 * 上限就会被压在 8 上。
 */
describe('effectiveEpisodes', () => {
  it('声明值有就用声明值', () => {
    expect(effectiveEpisodes(12, 8)).toBe(12)
  })

  it('声明值是 0(上游"没公布")才用本地条数', () => {
    expect(effectiveEpisodes(0, 12)).toBe(12)
  })

  it('本地条数不得压小声明值 —— 连载中只收到已播集数时, 上限仍是声明的总数', () => {
    // 犬夜叉这种: 声明 181, 本地真收齐 167。反过来的写法会返回 167
    expect(effectiveEpisodes(181, 167)).toBe(181)
    expect(effectiveEpisodes(24, 8)).toBe(24)
  })

  it('两个都没有 -> 0, 表示「不知道」而不是「0 集」', () => {
    expect(effectiveEpisodes(0, 0)).toBe(0)
    expect(effectiveEpisodes(null, null)).toBe(0)
    expect(effectiveEpisodes(undefined, undefined)).toBe(0)
    // 本地没缓存过那部番时后端**根本不发**这两个键, 取出来是 undefined
    expect(effectiveEpisodes(undefined, null)).toBe(0)
  })

  it('负数与非数字都归 0 —— 别让一个脏值变成负分母或 NaN 宽度', () => {
    expect(effectiveEpisodes(-5, -1)).toBe(0)
    expect(effectiveEpisodes('abc', '12abc')).toBe(0)
    expect(effectiveEpisodes(NaN, NaN)).toBe(0)
  })

  it('数字字符串照数(JSON 里没引号, 但表单回填给的是字符串)', () => {
    expect(effectiveEpisodes('12', '8')).toBe(12)
    expect(effectiveEpisodes('0', '12')).toBe(12)
  })
})
