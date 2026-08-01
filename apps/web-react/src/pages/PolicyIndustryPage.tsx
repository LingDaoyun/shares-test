import { useEffect, useState } from 'react'
import { ExternalLink, RefreshCw } from 'lucide-react'
import { fetchPolicyThemes } from '../api/client'
import { Button } from '../components/ui/Button'
import { Card } from '../components/ui/Card'
import { ScoreBadge, Tag } from '../components/ui/Badge'
import { Loader } from '../components/ui/Loader'
import { toast } from '../components/ui/Toast'
import { extractErrorMessage, formatDate, formatScore } from '../lib/format'
import type {
  PolicySignal,
  PolicyTheme,
  PolicyCompanyCandidate
} from '../types'

export function PolicyIndustryPage() {
  const [themes, setThemes] = useState<PolicyTheme[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')

  const loadDashboard = async () => {
    setLoading(true)
    setError('')
    try {
      const themeData = await fetchPolicyThemes()
      setThemes(themeData)
    } catch (e) {
      const message = extractErrorMessage(e)
      setError(message)
      toast.error(`政策看板加载失败：${message}`)
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    void loadDashboard()
  }, [])

  if (loading) {
    return <Loader text="正在加载政策产业看板" />
  }

  return (
    <div className="flex flex-col gap-4">
      <Card className="overflow-hidden">
        <div className="flex flex-col gap-4">
          <div className="flex flex-wrap items-start justify-between gap-3">
            <div>
              <div className="eyebrow mb-1">POLICY INDUSTRY BOARD</div>
              <h2 className="text-2xl font-semibold text-ink-900">政策产业看板</h2>
              <p className="mt-2 max-w-3xl text-sm leading-relaxed text-ink-500">
                从官方政策源提取产业方向，这里不直接给买卖建议，只沉淀长期价投可复核的主题假设、
                产业链能力和后续监控指标。
              </p>
            </div>
            <Button icon={<RefreshCw className="h-4 w-4" />} onClick={() => void loadDashboard()}>
              刷新政策源
            </Button>
          </div>
          {error ? <div className="rounded-lg border border-danger/20 bg-red-50 px-4 py-3 text-sm text-danger">{error}</div> : null}
          <div className="grid grid-cols-1 gap-3 md:grid-cols-3">
            <ProcessTile label="政策原文" value="官方源优先" detail="中国政府网、发改委、工信部等多源交叉" />
            <ProcessTile label="产业映射" value="龙头公司池" detail="按政策主题匹配行业位置、财报质量和可复核证据" />
            <ProcessTile label="输出边界" value="不荐股" detail="只生成行业方向、验证指标和公司筛选画像" />
          </div>
        </div>
      </Card>

      <Card title="政策主题">
        {themes.length ? (
          <div className="grid grid-cols-1 gap-3">
            {themes.map((theme) => <ThemeCard key={theme.themeCode} theme={theme} />)}
          </div>
        ) : (
          <EmptyText text="暂未从政策源提取到主题" />
        )}
      </Card>
    </div>
  )
}

function ThemeCard({ theme }: { theme: PolicyTheme }) {
  return (
    <article className="rounded-lg border border-line-soft bg-white p-4 transition hover:border-brand-200 hover:bg-brand-50/40">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h3 className="text-base font-semibold text-ink-900">{theme.name}</h3>
          <div className="mt-1 flex flex-wrap gap-1.5">
            <Tag tone="sky">{theme.policyLevel}</Tag>
            <Tag>{theme.timeHorizon}</Tag>
          </div>
        </div>
        <ScoreBadge value={theme.strengthScore} />
      </div>
      <div className="mt-3 flex flex-wrap gap-1.5">
        {theme.chainSegments.map((segment) => <Tag key={segment} tone="brand">{segment}</Tag>)}
      </div>
      <div className="mt-3 space-y-2">
        {theme.signals.slice(0, 3).map((signal) => <SignalRow key={`${signal.source}-${signal.summary}`} signal={signal} />)}
      </div>
      <CompanyPool candidates={theme.companyPool} />
      {theme.risks.length ? (
        <p className="mt-3 text-xs leading-relaxed text-ink-400">风险：{theme.risks.join('、')}</p>
      ) : null}
    </article>
  )
}

function CompanyPool({ candidates }: { candidates: PolicyCompanyCandidate[] }) {
  if (!candidates?.length) {
    return (
      <div className="mt-3 rounded-md border border-dashed border-line px-3 py-2 text-xs text-ink-400">
        公司池：暂未匹配到财报质量和行业位置均可复核的龙头候选
      </div>
    )
  }
  return (
    <div className="mt-3 rounded-lg border border-line-soft bg-white">
      <div className="flex items-center justify-between border-b border-line-soft px-3 py-2">
        <span className="text-xs font-semibold text-ink-500">公司池</span>
        <Tag tone="neutral">研究候选 · 不荐股</Tag>
      </div>
      <div className="divide-y divide-line-soft">
        {candidates.slice(0, 5).map((candidate) => (
          <CompanyPoolRow key={candidate.symbol} candidate={candidate} />
        ))}
      </div>
    </div>
  )
}

function CompanyPoolRow({ candidate }: { candidate: PolicyCompanyCandidate }) {
  return (
    <div className="grid grid-cols-1 gap-2 px-3 py-3 lg:grid-cols-[1fr_auto]">
      <div className="min-w-0">
        <div className="flex flex-wrap items-center gap-2">
          <span className="font-semibold text-ink-900">{candidate.companyName}</span>
          <span className="tabular text-xs text-ink-400">{candidate.symbol}</span>
          <Tag tone="sky">{candidate.chainSegment}</Tag>
          <Tag tone="neutral">{candidate.actionLabel}</Tag>
        </div>
        <p className="mt-1 text-xs text-ink-400">{candidate.industry ?? '行业待补'} · {candidate.researchRole}</p>
        <p className="mt-2 text-xs leading-relaxed text-ink-500">
          {candidate.leadershipRationale.slice(0, 3).join('；')}
        </p>
        {candidate.dataGaps.length ? (
          <p className="mt-1 text-xs leading-relaxed text-amber-700">待复核：{candidate.dataGaps.slice(0, 2).join('；')}</p>
        ) : null}
      </div>
      <div className="flex flex-wrap items-center gap-1.5 lg:justify-end">
        <Tag tone={candidate.financialQualityScore >= 80 ? 'success' : candidate.financialQualityScore >= 65 ? 'brand' : 'warning'}>
          {candidate.financialQualityLabel}
        </Tag>
        <Tag>财报 {formatScore(candidate.financialQualityScore)}</Tag>
        <Tag>PE {formatScore(candidate.peTtm)}</Tag>
        <Tag>PB {formatScore(candidate.pbRatio)}</Tag>
      </div>
    </div>
  )
}

function SignalRow({ signal }: { signal: PolicySignal }) {
  return (
    <div className="rounded-md border border-line-soft bg-surface-soft px-3 py-2">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <span className="text-xs font-semibold text-ink-500">{signal.source} · {signal.signalType}</span>
        <span className="tabular text-xs text-brand-600">{signal.confidence}</span>
      </div>
      <p className="mt-1 text-sm leading-relaxed text-ink-700">{signal.summary}</p>
      <div className="mt-1 flex items-center gap-2 text-xs text-ink-400">
        <span>{formatDate(signal.publishedAt)}</span>
        {signal.url ? (
          <a
            className="inline-flex items-center gap-1 text-brand-600 hover:text-brand-700"
            href={signal.url}
            target="_blank"
            rel="noreferrer"
          >
            来源 <ExternalLink className="h-3 w-3" />
          </a>
        ) : null}
      </div>
    </div>
  )
}

function ProcessTile({ label, value, detail }: { label: string; value: string; detail: string }) {
  return (
    <div className="rounded-lg border border-line-soft bg-surface-soft p-4">
      <div className="text-xs font-medium text-ink-400">{label}</div>
      <div className="mt-1 text-lg font-semibold text-ink-900">{value}</div>
      <p className="mt-1 text-xs leading-relaxed text-ink-500">{detail}</p>
    </div>
  )
}

function EmptyText({ text }: { text: string }) {
  return <div className="rounded-lg border border-dashed border-line px-4 py-8 text-center text-sm text-ink-400">{text}</div>
}
