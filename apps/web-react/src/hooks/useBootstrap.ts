import { useEffect } from 'react'
import { useAppStore } from '../store/appStore'

let hasBootstrapped = false

// 应用启动：恢复缓存 + 并行加载全部数据 + LLM/运行时配置。
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
