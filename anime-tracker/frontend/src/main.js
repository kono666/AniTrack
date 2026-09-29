import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import router from './router'
// ── 字体 ──
// 改前是 @fontsource/geist-sans —— Vercel 的品牌字体, 也就是 shadcn/Next 那套
// 模板的出厂字体. 它跟这个站没有任何关系, 而且**没有中文字形**: 全站中文其实
// 一直在回退到系统字体, 只是没人注意到.
//
// 现在两个角色分开:
//   Saira Condensed  显示体(标题、大数字) —— 窄体, "编辑感"主要靠它
//   IBM Plex Sans    正文体(界面文字、表格数字)
//
// 只引 **latin- 子集**: 不带前缀的入口会把 latin / latin-ext / cyrillic /
// greek / vietnamese 五套字形全打包进来, 而一个中文站用不到其中四套。
// 中文不进 webfont, 走系统栈(tokens.css 里有说明和代价).
//
// 注意 IBM Plex Sans 官方最粗只到 700, 没有 800/900. 所以 font-weight 800/900
// 只应该出现在显示体上; 落在正文体上的会被浏览器伪粗体合成. 各组件改版时
// 逐个把这类字挪到显示体, 这里先把两套字重都备齐。
import '@fontsource/saira-condensed/latin-600.css'
import '@fontsource/saira-condensed/latin-700.css'
import '@fontsource/saira-condensed/latin-800.css'
import '@fontsource/saira-condensed/latin-900.css'
import '@fontsource/ibm-plex-sans/latin-400.css'
import '@fontsource/ibm-plex-sans/latin-500.css'
import '@fontsource/ibm-plex-sans/latin-600.css'
import '@fontsource/ibm-plex-sans/latin-700.css'
import './assets/css/style.css'

const app = createApp(App)
app.use(createPinia())
app.use(router)
app.mount('#app')
