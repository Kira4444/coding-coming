import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// 开发期后端端口。Windows 上 8054-8153 被 Hyper-V 预留，8080 绑不上，
// 所以本机开发用 8280；容器部署仍是 8080（见 nginx.conf）。
const devApiPort = Number(process.env.CODING_AGENT_DEV_PORT ?? 8280)

export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: `http://localhost:${devApiPort}`,
        changeOrigin: true,
      },
    },
  },
  build: {
    outDir: 'dist',
    sourcemap: false,
  },
})
