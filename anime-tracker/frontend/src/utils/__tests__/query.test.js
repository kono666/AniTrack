import { describe, it, expect } from 'vitest'
import { strParam, pageParam } from '../query'

/**
 * URL query 取值的两个口径.
 *
 * 这一组看着像在测两个一行函数, 但它钉的是**一个真实的坑**: `route.query.x`
 * 不是"字符串或者 undefined", 它有第三种形态 —— 同一个参数在地址栏里出现两次
 * (?tag=a&tag=b)时 vue-router 给的是**数组**. 而数组是真值, 于是
 * `if (route.query.tag)` 那种写法会放它过去, 拼进请求里成了 "a,b".
 */
describe('strParam', () => {
  it('单个字符串原样返回(空串也是空串)', () => {
    expect(strParam('治愈')).toBe('治愈')
    expect(strParam('')).toBe('')
  })

  it('数组当作「没有这个参数」', () => {
    expect(strParam(['a', 'b'])).toBe('')
  })

  it('undefined / null 当作「没有这个参数」', () => {
    expect(strParam(undefined)).toBe('')
    expect(strParam(null)).toBe('')
  })
})

describe('pageParam', () => {
  it('正整数字符串照数', () => {
    expect(pageParam('3')).toBe(3)
    expect(pageParam('1')).toBe(1)
  })

  it('缺省 / 空串 / 非数字 / 0 / 负数 一律回第 1 页', () => {
    // 与 Search 改动前的行为一致: 页码不合法时**按第 1 页处理**, 而不是拒绝整条 URL ——
    // 地址栏是用户能随手改的地方, 为一个 page=abc 把页面变成错误态太凶了
    expect(pageParam(undefined)).toBe(1)
    expect(pageParam('')).toBe(1)
    expect(pageParam('abc')).toBe(1)
    expect(pageParam('0')).toBe(1)
    expect(pageParam('-2')).toBe(1)
  })

  it('数组同样回第 1 页', () => {
    expect(pageParam(['2', '3'])).toBe(1)
  })

  it('小数取整数部分', () => {
    expect(pageParam('2.7')).toBe(2)
  })
})
