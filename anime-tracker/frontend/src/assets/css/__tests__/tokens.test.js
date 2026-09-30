// 这个文件只读 CSS 文本, 不需要 DOM.
// 顺带也是必须的: 默认的 jsdom 环境会把 import.meta.url 换成 http://localhost/...,
// 于是下面按路径找 tokens.css 会报 "The URL must be of scheme file" —— 实测报的是这个.
// @vitest-environment node
import { readFileSync } from 'node:fs'
import { describe, it, expect } from 'vitest'

/**
 * tokens.css 的一条不变量: 别名不能只写在 :root 里.
 *
 * 为什么值得单独一个测试文件: 2026-09-30 修的就是这个 —— `--card-bg: var(--card)`
 * 与 `--focus-ring: var(--primary)` 都只在 :root 里写了一份, 于是浅色主题下它们
 * 冻在深色值上: 后台的用户表/评论面板成了 #171514 深底配 #211e1e 的字(约 1.1:1,
 * 内容整个看不见), 键盘焦点框成了压在近白页面上的近白色.
 *
 * 机制: 自定义属性里的 var() 是在**声明它的那个元素**上替换掉的, 之后按字面值
 * 往下继承. 所以 :root 上的别名在 <html> 就定死了, body.light 再改 --b 也追不
 * 回来. 而它的坏法是"只在浅色主题下坏、不报错、不动任何测试" —— 开发机上多半
 * 是深色主题, 改的时候根本看不见. 所以钉在形式这一层.
 *
 * 它管的是**形式**, 不管某个具体色值好不好看.
 */

// 注释要先去掉: tokens.css 的注释里成段地引用了被删掉的那两个别名(那是写给
// 后来的人看的), 不剥掉的话下面那条正则会从注释里读出一堆并不存在的声明.
const css = readFileSync(new URL('../tokens.css', import.meta.url), 'utf8')
  .replace(/\/\*[\s\S]*?\*\//g, '')

/** 取出某个选择器的声明块. tokens.css 里这几块都是平铺的, 没有嵌套规则也没有 @media. */
function blockOf(selector) {
  const at = css.indexOf(`${selector} {`)
  if (at < 0) throw new Error(`tokens.css 里找不到选择器 ${selector}`)
  const open = css.indexOf('{', at)
  const close = css.indexOf('}', open)
  if (close < 0) throw new Error(`${selector} 的声明块没有闭合`)
  return css.slice(open + 1, close)
}

/** 块里的自定义属性声明 → Map<名字, 值> */
function declarations(block) {
  const out = new Map()
  for (const m of block.matchAll(/(--[\w-]+)\s*:\s*([^;]+);/g)) {
    out.set(m[1], m[2].trim())
  }
  return out
}

const root = declarations(blockOf(':root'))
const light = declarations(blockOf('body.light'))

describe('tokens.css 的主题不变量', () => {
  // 先证明解析本身没跑偏. 没有这两条, 下面那条断言完全可能在一片空集上"通过",
  // 而且将来谁把选择器改个名, 它也会继续绿.
  it('两个主题块都解析出来了', () => {
    expect(root.size).toBeGreaterThan(30)
    expect(light.has('--card')).toBe(true)
    expect(light.has('--primary')).toBe(true)
    expect(root.get('--card')).not.toBe(light.get('--card'))
  })

  it('引用了会随主题变的 token 的别名, 必须在 body.light 里也写一份', () => {
    const aliases = [...root].filter(([, value]) => value.includes('var('))
    expect(aliases.length).toBeGreaterThan(0)

    const offenders = aliases
      .filter(([name, value]) => {
        const refs = [...value.matchAll(/var\((--[\w-]+)\)/g)].map(m => m[1])
        // 只要引用的 token 里**有任何一个**在浅色块里被改写, 这个别名就会在
        // :root 上冻住 —— 它必须自己也在浅色块里重写一遍才跟得上.
        // --transition / --transition-slow 引的是 --dur / --ease, 那两个不随主题
        // 变, 所以不算, 也不需要重写.
        return refs.some(r => light.has(r)) && !light.has(name)
      })
      .map(([name]) => name)

    expect(offenders).toEqual([])
  })
})
