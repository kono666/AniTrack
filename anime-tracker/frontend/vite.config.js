import { fileURLToPath } from 'node:url'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

/**
 * 单个图标的落点.
 *
 * 为什么需要它: `@phosphor-icons/vue` 的 `package.json` 里 `exports` 只声明了
 * `.` 和 `./compact` 两个入口, 所以 `@phosphor-icons/vue/dist/icons/PhStar.vue.mjs`
 * 这种子路径会被**明确拒绝**(ERR_PACKAGE_PATH_NOT_EXPORTED —— 实测报这个).
 * 别名在包解析之前生效, 于是绕开了那道门; 指到的正是那个桶内部自己用的路径
 * (它的第一行就是 `import ... from "./icons/PhAcorn.vue.mjs"`).
 *
 * 为什么要绕: 那个桶里有 3024 个图标模块, 每个把 6 种字重全内联进去. 生产构建会
 * tree-shake 掉没用的(实测 vendor-icons 202 KB), 但**开发模式不 tree-shake** ——
 * Vite 预打包出来的是一个 7.2 MB 的文件, 加上内联的 sourcemap, 浏览器实际要收
 * 20.8 MB. 改成按需引入之后, 开发下要传的就是用到的这二十几个模块.
 *
 * 这是**开发体验**的改动, 不是线上体积的改动: 生产那一侧本来就是 202 KB.
 */
const iconsDir = fileURLToPath(
  new URL('./node_modules/@phosphor-icons/vue/dist/icons', import.meta.url)
)

// 从模块 id 里取出**包名**.
//
// 为什么不直接 `id.includes('/vue/')`: 那是拿子串当包名用, 会误伤 ——
// @phosphor-icons/vue/dist/icons/PhStar.vue.mjs 里也有 '/vue/'.
// lastIndexOf 是因为依赖里可能还有嵌套的 node_modules, 取最里面那层才对.
function packageName(id) {
  const p = id.replace(/\\/g, '/') // Windows 上传进来的 id 是反斜杠
  const i = p.lastIndexOf('node_modules/')
  if (i < 0) return null
  const seg = p.slice(i + 'node_modules/'.length).split('/')
  if (!seg[0]) return null
  return seg[0].startsWith('@') ? `${seg[0]}/${seg[1] || ''}` : seg[0]
}

export default defineConfig({
  // vitest 配置 (仅测试时生效)
  test: {
    environment: 'jsdom',
    globals: true,
  },
  plugins: [vue()],
  resolve: {
    alias: {
      '@icons': iconsDir,
    },
  },
  build: {
    // 生产构建**不发** sourcemap.
    //
    // 这条不是"省一点体积", 而是"要不要把源码放到线上": .map 一旦对外可访问,
    // 整个 src 连同注释一起就摊开了 —— 这个仓库注释写得很密, 里面是取舍和
    // "这里当初为什么踩坑". 换来的只是"生产报错能还原到行号".
    // 这是个演示项目, 出错现场靠后端响应和容器日志就够定位, 所以选不发.
    // 真要发也别写 true(那是"顺手发出去"), 用 'hidden': 照样生成 .map,
    // 但不在 js 末尾写 //# sourceMappingURL=, 什么时候要排查什么时候再手动关联.
    sourcemap: false,

    rollupOptions: {
      output: {
        // 把"不跟着业务代码变"的依赖拆成单独的 chunk, 让它们跨版本留在浏览器缓存里.
        //
        // 不拆的话这些库全在 index-*.js 里(158403 字节), 而 index 的文件名带内容
        // 哈希, 改一行业务代码它就变 —— 用户每发一次版都得重新下载整个 index.
        // 拆开之后, 动页面只让页面 chunk 失效, 几个 vendor 的缓存照旧命中.
        // (实测: index 158403 -> 10980 字节, api 52374 -> 2290.)
        //
        // 代价也量过: 总 JS 296952 -> 298909 字节(+1957, +0.66%), 文件 19 -> 21.
        // 多出来的是 chunk 边界自己的样板 —— 每个 chunk 得自带一层包装与导出,
        // 压缩出来的短变量名也没法跨 chunk 共用. 换到的是首屏少下一个十几万字节
        // 的包, 划算.
        //
        // 分组按"一起升级"来分, 不按包分: vue / vue-router / pinia 的版本是绑在
        // 一起动的, 拆成三个 chunk 只会多两次请求, 换不来任何额外命中.
        //
        // 两个坑记在这里:
        //
        // 一、必须写成**函数**. rolldown 的 manualChunks 不收 { 名字: [包名] } 那种
        //     对象写法(rollup 收), 给了对象不是忽略而是直接构建失败 ——
        //     "Invalid type: Expected Function but received Object" 之后再 TypeError.
        //     好在是响的, 不是静默失效.
        //
        // 二、判断按包名解析, 不用 '/vue/' 这种子串(见上面 packageName).
        //     现在图标那条写在前面, 恰好把 @phosphor-icons/vue 挡住了; 但两条顺序
        //     一颠倒, 图标会整个并进 vendor-vue(实测: 三块变两块, 一块 169.74KB),
        //     图标独立的缓存周期就这么无声无息地没了. 靠顺序避开的坑不算避开.
        //
        // 三、这里有个**返回值与最终归属对不上**的地方, 记下来免得下次当成 bug 查:
        //     下面 @vue/* 四个包全都返回 vendor-vue, 而实测 rolldown 只把
        //     runtime-dom 放进了 vendor-vue, reactivity / runtime-core / shared
        //     三个都被它放进了 vendor-icons. 也就是说 125.99KB 的 vendor-icons 里
        //     装的是"Vue 核心运行时 + 17 个图标", 名字和内容对不上.
        //     用两种写法(子串匹配 / 包名匹配)各构建一次, 落点逐字节一致(连文件名
        //     哈希都相同), 所以不是匹配写错了, 是 rolldown 自己的分配.
        //     没有为这个再改配置: 每个依赖模块只在一个 chunk 里出现一次(实测无
        //     跨 chunk 重复, 上面那 +1957 字节因此不是重复代码而是边界样板), 该
        //     命中的缓存照样命中. 但要讲清一件事: 图标和 Vue 核心既然同在一块,
        //     "图标很少动, 值得单独一个缓存周期"这个理由就只剩一半 —— 升一次 Vue,
        //     图标也得跟着重新下载.
        //     升级 vite/rolldown 之后值得再看一眼: 构建输出里三块的体积会直接说明
        //     分配有没有变.
        manualChunks(id) {
          const pkg = packageName(id)
          if (!pkg) return
          if (pkg === '@phosphor-icons/vue') return 'vendor-icons'
          if (pkg === 'axios') return 'vendor-axios'
          if (pkg === 'vue' || pkg === 'vue-router' || pkg === 'pinia' ||
              pkg === 'vue-demi' || pkg.startsWith('@vue/')) {
            return 'vendor-vue'
          }
        },
      },
    },

    // 刻意**不**删 console.*, 尽管这是构建配置里最常见的"优化"之一.
    //
    // 一、清单里那条 esbuild.drop: ['console'] 在 vite 8 上**静默失效**:
    //     本仓库用的 vite 8 是 rolldown 构建, esbuild 选项已经不参与压缩,
    //     写了不报错、不警告, 产物里的 console 一个不少. 真正生效的写法是
    //     build.rollupOptions.output.minify.compress.dropConsole, 但它只省下
    //     406 字节 —— 296952 -> 296546, 0.14%, gzip 之后差别更小.
    //
    //     顺带记一个坑: build.minify 直接传对象是**另一个意思** ——
    //     传了就等于把整个压缩关掉, 产物从 296952 字节变成 601505 字节、
    //     8225 行未压缩代码. 想只调 dropConsole, 必须走上面那条完整路径.
    //
    // 二、删掉的正好是**报错的那几行**. 源码里 console.* 共 5 处
    //     (Assistant.vue 3 处、Home.vue 2 处), 全在 catch 里, 是"接口挂了但
    //     界面只是空着"这类问题唯一的线索; 产物里另有 6 处来自依赖(axios 的
    //     warn 等). 为 0.14% 的体积把这些线索一起删掉, 不划算.
    //
    // 所以这里只留结论: 不删. 将来真要做, 值得做的也不是删日志,
    // 而是把散在各页 catch 里的 console.error 收敛成一个能带上下文上报的
    // 地方 —— 那是另一件事, 不该混在构建配置里.
  },
  server: {
    port: 5173,
    proxy: {
      // /api/* 走 Java 后端
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
})
