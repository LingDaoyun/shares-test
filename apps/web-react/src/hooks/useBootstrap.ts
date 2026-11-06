import { useEffect } from 'react'
import { useAppStore } from '../store/appStore'

let hasBootstrapped = false

// 应用启动：读取大模型配置与运行时配置。
// 大模型配置用于政策解读与热点研判；未配置时仍可读取已保存结果和原始新闻。
export function useBootstrap() {
  const refreshLlmConfig = useAppStore((s) => s.refreshLlmConfig)
  const loadRuntimeConfig = useAppStore((s) => s.loadRuntimeConfig)

  useEffect(() => {
    if (hasBootstrapped) return
    hasBootstrapped = true
    void refreshLlmConfig()
    void loadRuntimeConfig()
  }, [refreshLlmConfig, loadRuntimeConfig])
}
