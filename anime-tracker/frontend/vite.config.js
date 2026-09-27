import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  // vitest 配置 (仅测试时生效)
  test: {
    environment: 'jsdom',
    globals: true,
  },
  plugins: [vue()],
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
