import { useEffect } from 'react'
import { useAppStore } from '../store/appStore'

let hasBootstrapped = false

// 应用启动：恢复缓存 + 并行加载全部数据 + 运行时配置。
// 大模型配置未被任何页面使用，不再加载，未配置也不影响功能。
export function useBootstrap() {
  const loadAll = useAppStore((s) => s.loadAll)
  const loadRuntimeConfig = useAppStore((s) => s.loadRuntimeConfig)

  useEffect(() => {
    if (hasBootstrapped) return
    hasBootstrapped = true
    void loadAll()
    void loadRuntimeConfig()
  }, [loadAll, loadRuntimeConfig])
}
