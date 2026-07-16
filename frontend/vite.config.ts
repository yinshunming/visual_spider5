import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// 构建产物直接写入 Spring Boot 输出目录，随 JAR 打包（单 JAR 交付）。
export default defineConfig({
  plugins: [vue()],
  build: {
    outDir: '../target/classes/static',
    emptyOutDir: true,
  },
  server: {
    port: 5173,
    proxy: {
      '/ws': { target: 'ws://localhost:8080', ws: true },
    },
  },
})
