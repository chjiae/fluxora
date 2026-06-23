import { fileURLToPath, URL } from 'node:url'
import vue from '@vitejs/plugin-vue'
import { defineConfig } from 'vitest/config'
import AutoImport from 'unplugin-auto-import/vite'
import { NaiveUiResolver } from 'unplugin-vue-components/resolvers'
import Components from 'unplugin-vue-components/vite'

export default defineConfig({
  plugins: [
    vue(),
    AutoImport({ imports: ['vue', 'vue-router', 'pinia', { 'naive-ui': ['useDialog', 'useMessage', 'useNotification', 'useLoadingBar'] }] }),
    Components({ resolvers: [NaiveUiResolver()] }),
  ],
  resolve: { alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) } },
  // 大型 Vue SFC 会共享自动导入/组件转换缓存；Windows 下多 worker 并发转换会产生严重资源争用并误报超时。
  test: { environment: 'happy-dom', globals: true, testTimeout: 30000, maxWorkers: 1, fileParallelism: false, exclude: ['e2e/**', 'node_modules/**'] },
})
