import { useEffect } from 'react'
import { useAppStore } from '../store/appStore'

let hasBootstrapped = false

// 应用启动：恢复缓存 + 并行加载全部数据 + 运行时配置。
// 大模型配置只影响政策解读的可选 AI 复核，未配置或加载失败都不影响其他功能。
export function useBootstrap() {
  const loadAll = useAppStore((s) => s.loadAll)
  const refreshLlmConfig = useAppStore((s) => s.refreshLlmConfig)
  const loadRuntimeConfig = useAppStore((s) => s.loadRuntimeConfig)

  useEffect(() => {
    if (hasBootstrapped) return
    hasBootstrapped = true
    void loadAll()
    void refreshLlmConfig()
    void loadRuntimeConfig()
  }, [loadAll, refreshLlmConfig, loadRuntimeConfig])
}
