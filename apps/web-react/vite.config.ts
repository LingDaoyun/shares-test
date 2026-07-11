import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// 与旧 Vue 版保持一致：/api 代理到后端 19080；dev 端口设 5176 避开旧版。
export default defineConfig({
  plugins: [react()],
  server: {
    host: '127.0.0.1',
    port: 5176,
    strictPort: true,
    proxy: {
      '/api': {
        target: 'http://127.0.0.1:19080',
        changeOrigin: true
      }
    }
  }
})
