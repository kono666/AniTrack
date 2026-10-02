import { describe, it, expect, afterEach, vi } from 'vitest'
import { BGM_WEEKDAYS, bgmWeekdayId } from '../bgmWeekday'

/**
 * 星期换算: 全站唯一那份, 首页与放送表都从它取.
 *
 * 为什么值得单独一个文件, 而不是只在 Home / Calendar 的用例里顺带覆盖:
 *
 *   一、那两处钉的都是**星期三**(getDay() → 3). 而这段换算里唯一会出错的地方正是
 *       **星期天** —— `getDay()` 是 0, 而 Bangumi 的 id 是 7, 中间隔着那个三元.
 *       少了这一条, "周日" 那天整个放送表会静默空着, 而一年里只有约 1/7 的日子
 *       跑得到, 恰好跑测试的那天多半不是.
 *   二、返回值必须是**字符串**. 后端声明成 `Map<String,String>`, Jackson 把 JSON 里
 *       的数字 3 强制转成 `"3"`; 这里若回数字, `find` 只回 undefined —— 不报错,
 *       区块整个消失. 这是这个函数历史上真出过的那类错(当年是拿 '周三' 比 '星期三'),
 *       所以类型本身也要钉.
 */
describe('bgmWeekdayId', () => {
  let spy

  afterEach(() => spy?.mockRestore())

  /** 钉死"今天是星期几", 不跟着运行那天跑 */
  function onGetDay(n) {
    spy = vi.spyOn(Date.prototype, 'getDay').mockReturnValue(n)
  }

  it('周一到周六: getDay() 的 1..6 原样就是 id 1..6', () => {
    for (const dow of [1, 2, 3, 4, 5, 6]) {
      onGetDay(dow)
      expect(bgmWeekdayId()).toBe(String(dow))
      spy.mockRestore()
    }
  })

  it('星期天是 7 不是 0 —— getDay() 与 Bangumi 的 id 在这里差一次换算', () => {
    onGetDay(0)
    expect(bgmWeekdayId()).toBe('7')
  })

  it('返回的是字符串, 不是数字', () => {
    onGetDay(3)
    // 数字 3 与 '3' 用 === 比是 false, 而 find 不中时只回 undefined, 不报错
    expect(bgmWeekdayId()).toStrictEqual('3')
    expect(typeof bgmWeekdayId()).toBe('string')
  })

  it('BGM_WEEKDAYS 是七格, id 从 "1" 到 "7" 且都是字符串', () => {
    expect(BGM_WEEKDAYS).toHaveLength(7)
    expect(BGM_WEEKDAYS.map(d => d.id)).toEqual(['1', '2', '3', '4', '5', '6', '7'])
    // 放送表按这个顺序画七格 —— 顺序错了页面上看不出来(每天都还在, 只是星期几对不上)
    expect(BGM_WEEKDAYS.map(d => d.cn)).toEqual([
      '星期一', '星期二', '星期三', '星期四', '星期五', '星期六', '星期日',
    ])
  })

  it('每一个 id 都对应得上一个真实的 getDay() 取值', () => {
    // 反向: 七个 id 各自能被某个 getDay() 取到, 没有孤儿格.
    // 少了这条, 把周日写成 id 0(或漏掉)时上面那条"七格齐全"仍然绿.
    const seen = new Set()
    for (const dow of [0, 1, 2, 3, 4, 5, 6]) {
      onGetDay(dow)
      seen.add(bgmWeekdayId())
      spy.mockRestore()
    }
    expect([...seen].sort()).toEqual(BGM_WEEKDAYS.map(d => d.id).sort())
  })
})
