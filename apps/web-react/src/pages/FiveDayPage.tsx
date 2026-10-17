import { useCallback, useEffect, useRef, useState } from 'react'
import { RefreshCw } from 'lucide-react'
import { fetchFiveDaySnapshot, startFiveDayScan } from '../api/fiveDay'
import { FiveDayContextPanel } from '../components/fiveday/FiveDayContextPanel'
import { FiveDayCandidateTable } from '../components/fiveday/FiveDayCandidateTable'
import { FiveDayDetailOverlay } from '../components/fiveday/FiveDayDetailOverlay'
import { FiveDayHistoryPanel } from '../components/fiveday/FiveDayHistoryPanel'
import { FiveDayRulesPanel } from '../components/fiveday/FiveDayRulesPanel'
import { Tag } from '../components/ui/Badge'
import { Button } from '../components/ui/Button'
import { Card } from '../components/ui/Card'
import { Loader } from '../components/ui/Loader'
import { SectionBanner } from '../components/ui/SectionBanner'
import { extractErrorMessage, formatDateTime } from '../lib/format'
import type { FiveDayScope, FiveDaySnapshot } from '../types/fiveDay'

type CandidateView = 'funds' | 'control' | 'missing'
type RequestMode = 'read' | 'scan'

const STATUS_LABELS: Record<FiveDaySnapshot['status'], string> = {
  IDLE: '尚未扫描',
  SCANNING: '扫描中',
  PREPARING: '数据准备中',
  READY: '扫描完成',
  FAILED: '扫描未完成'
}

export function FiveDayPage() {
  const [scope, setScope] = useState<FiveDayScope>({ strategy: 'v2', includeGrowth: false, includeStar: false })
  const [snapshot, setSnapshot] = useState<FiveDaySnapshot | null>(null)
  const [requestMode, setRequestMode] = useState<RequestMode | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [view, setView] = useState<CandidateView>('funds')
  const [selectedSymbol, setSelectedSymbol] = useState<string | null>(null)
  const requestGeneration = useRef(0)

  const loadSnapshot = useCallback(async (mode: RequestMode) => {
    const generation = ++requestGeneration.current
    setRequestMode(mode)
    setError(null)
    if (mode === 'scan') {
      setSelectedSymbol(null)
      setSnapshot(null)
    }
    try {
      const next = await (mode === 'scan' ? startFiveDayScan(scope) : fetchFiveDaySnapshot(scope))
      if (generation !== requestGeneration.current) return
      setSnapshot(next)
    } catch (caught) {
      if (generation === requestGeneration.current) setError(extractErrorMessage(caught))
    } finally {
      if (generation === requestGeneration.current) setRequestMode(null)
    }
  }, [scope])

  useEffect(() => {
    void loadSnapshot('read')
    return () => { requestGeneration.current += 1 }
  }, [loadSnapshot])

  useEffect(() => {
    if (snapshot?.status !== 'SCANNING' || error || requestMode !== null) return
    const timer = window.setTimeout(() => void loadSnapshot('read'), 5000)
    return () => window.clearTimeout(timer)
  }, [snapshot, error, requestMode, loadSnapshot])

  const updateScope = (next: FiveDayScope) => {
    requestGeneration.current += 1
    setSnapshot(null)
    setSelectedSymbol(null)
    setError(null)
    setView('funds')
    setScope(next)
  }

  const isV2 = scope.strategy === 'v2'
  const assessments = snapshot?.context?.assessments ?? []
  const eligibleSymbols = new Set(assessments.filter((item) => item.decision === 'ELIGIBLE').map((item) => item.symbol))
  const candidates = snapshot?.candidates ?? []
  const counts: Record<CandidateView, number> = {
    funds: candidates.filter((candidate) => isV2 ? eligibleSymbols.has(candidate.symbol) : candidate.flowDataComplete && candidate.matchesFundFlow).length,
    control: isV2 ? candidates.length : candidates.filter((candidate) => candidate.flowDataComplete).length,
    missing: candidates.filter((candidate) => !candidate.flowDataComplete).length
  }
  const visibleCandidates = candidates.filter((candidate) => view === 'funds'
    ? isV2 ? eligibleSymbols.has(candidate.symbol) : candidate.flowDataComplete && candidate.matchesFundFlow
    : view === 'control' ? isV2 || candidate.flowDataComplete : !candidate.flowDataComplete)
  const selected = candidates.find((candidate) => candidate.symbol === selectedSymbol) ?? null
  const busy = requestMode !== null
  const scanning = snapshot?.status === 'SCANNING'
  const scanLabel = scanning ? '扫描中' : snapshot?.status === 'READY' ? '重新扫描' : snapshot?.status === 'FAILED' || snapshot?.status === 'PREPARING' ? '重试扫描' : '开始扫描'
  const ready = snapshot?.status === 'READY'
  const coverage = snapshot?.coverage
  const scopeKey = `${scope.strategy}-${scope.includeGrowth}-${scope.includeStar}`

  return (
    <div className="flex flex-col gap-4">
      <SectionBanner
        eyebrow="FIVE TRADING DAYS"
        title="五日观察"
        description={isV2 ? '结合完整收盘数据、市场环境、行业主线与公开公告，按五个交易日跟踪观察结果。' : '用完整收盘数据观察横盘、换手提升和资金条件，按五个交易日跟踪结果。'}
        extra={(
          <div className="flex flex-wrap items-center gap-2">
            <Button type="button" loading={requestMode === 'read'} disabled={busy} onClick={() => void loadSnapshot('read')}>读取状态</Button>
            <Button type="button" variant="primary" icon={<RefreshCw className="h-4 w-4" />} loading={requestMode === 'scan'} disabled={busy || scanning} onClick={() => void loadSnapshot('scan')}>{scanLabel}</Button>
          </div>
        )}
      />

      <Card>
        <div className="mb-4 flex flex-wrap gap-2" aria-label="观察策略版本">
          <Button type="button" variant={isV2 ? 'primary' : 'secondary'} aria-pressed={isV2} onClick={() => updateScope({ ...scope, strategy: 'v2' })}>v2 环境与主线</Button>
          <Button type="button" variant={!isV2 ? 'primary' : 'secondary'} aria-pressed={!isV2} onClick={() => updateScope({ ...scope, strategy: 'v1' })}>v1 纯量价对照</Button>
        </div>
        <div className="flex flex-wrap items-center justify-between gap-4">
          <div>
            <h3 className="text-sm font-semibold text-ink-900">候选范围</h3>
            <p className="mt-1 text-xs leading-6 text-ink-500">主板默认启用；板块开关读取各自快照，需要时手动启动扫描。</p>
          </div>
          <div className="flex flex-wrap gap-5">
            <label className="flex cursor-pointer items-center gap-2 text-sm text-ink-700">
              <input type="checkbox" className="h-4 w-4 accent-brand-600" checked={scope.includeGrowth} onChange={(event) => updateScope({ ...scope, includeGrowth: event.target.checked })} />
              创业板 <span className="text-xs text-ink-400">300 / 301</span>
            </label>
            <label className="flex cursor-pointer items-center gap-2 text-sm text-ink-700">
              <input type="checkbox" className="h-4 w-4 accent-brand-600" checked={scope.includeStar} onChange={(event) => updateScope({ ...scope, includeStar: event.target.checked })} />
              科创板 <span className="text-xs text-ink-400">688 / 689</span>
            </label>
          </div>
        </div>
      </Card>

      {error ? <Card className="border-red-200 bg-red-50"><p role="alert" className="text-sm text-red-700">请求未完成：{error}。可手动读取状态或重试扫描。</p></Card> : null}
      {busy && !snapshot ? <Card><Loader text={requestMode === 'scan' ? '正在启动五日观察扫描' : '读取五日观察状态'} /></Card> : null}

      {snapshot ? (
        <Card title="信号时点与覆盖">
          <div className="flex flex-wrap items-center gap-3 text-sm">
            <Tag tone={ready ? 'success' : snapshot.status === 'FAILED' ? 'warning' : 'neutral'}>{STATUS_LABELS[snapshot.status] || '状态待核实'}</Tag>
            <span className="tabular text-ink-700">信号日 {snapshot.signalDate || '待核实'}</span>
            <span className="tabular text-xs text-ink-500">快照生成 {formatDateTime(snapshot.generatedAt)}</span>
            {ready ? <Tag tone={snapshot.forwardEligible ? 'brand' : 'neutral'}>{snapshot.forwardEligible ? '前瞻样本' : '不纳入前瞻统计'}</Tag> : null}
          </div>
          {snapshot.message ? <p className="mt-3 text-sm leading-6 text-ink-600" role="status">{snapshot.message}</p> : null}
          {snapshot.status === 'IDLE' ? <p className="mt-2 text-xs text-ink-500">点击“开始扫描”生成当前范围的观察快照。</p> : null}
          {snapshot.status === 'SCANNING' ? <p className="mt-2 text-xs text-ink-500">扫描在后台进行，页面每 5 秒读取一次进度。</p> : null}
          {snapshot.status === 'PREPARING' ? <p className="mt-2 text-xs text-ink-500">当前数据尚未准备完成，可稍后手动重试；页面不会自动反复启动扫描。</p> : null}
          {coverage ? (
            <div className="mt-4 grid grid-cols-2 gap-3 border-t border-line-soft pt-4 sm:grid-cols-4 xl:grid-cols-7">
              {([
                ['行情池', coverage.universeCount],
                [isV2 ? '主线价格预选' : '价格预选', coverage.priceQualifiedCount],
                ['已复核', coverage.reviewedCount],
                ['量价合格', coverage.technicalQualifiedCount],
                ['资金合格', coverage.fundFlowQualifiedCount],
                ['数据缺失', coverage.missingDataCount],
                ['未复核', coverage.unreviewedCount]
              ] as const).map(([label, value]) => (
                <div key={label}>
                  <div className="text-xs text-ink-500">{label}</div>
                  <div className={`tabular mt-1 text-lg font-semibold ${label === '未复核' && value > 0 ? 'text-amber-700' : 'text-ink-900'}`}>{value}</div>
                </div>
              ))}
            </div>
          ) : null}
          <p className="mt-3 text-xs leading-6 text-ink-500">{isV2 ? '强势行业间轮询分配名额，最多复核 ' : '最多复核 '}{snapshot.rules?.maximumReviewed ?? 100}{isV2 ? ' 只主线价格预选股；未复核仅指主线预选中因容量未检查的股票。全部形态合格数及行业过滤数见本次扫描说明。' : ' 只价格预选股；未复核数量不代表不合格，数据缺失也不作通过推断。'}</p>
          {snapshot.dataGaps.length > 0 ? <ul className="mt-2 list-disc space-y-1 pl-5 text-xs leading-6 text-amber-800">{snapshot.dataGaps.map((gap, index) => <li key={index}>{gap}</li>)}</ul> : null}
        </Card>
      ) : null}

      {isV2 && snapshot ? <FiveDayContextPanel context={snapshot.context} signalDate={snapshot.signalDate} /> : null}

      <Card title="观察候选" flush>
        <div className="px-5 py-4">
          <div className="flex flex-wrap gap-2" aria-label="候选数据筛选">
            {([
              ['funds', isV2 ? '执行条件齐备' : '资金条件合格'],
              ['control', isV2 ? '全部主线量价观察' : '量价对照（资金完整）'],
              ['missing', '资金缺失']
            ] as const).map(([key, label]) => (
              <Button key={key} type="button" variant={view === key ? 'primary' : 'secondary'} aria-pressed={view === key} onClick={() => { setView(key); setSelectedSymbol(null) }}>{label} · {counts[key]}</Button>
            ))}
          </div>
          <p className="mt-3 text-xs leading-6 text-ink-500">
            {isV2 ? '默认仅展示条件判断为 ELIGIBLE 的执行条件齐备组，仍非下单指令。全部主线量价观察包含继续观察、风险待核实及数据未知；资金条件合格本身不代表推荐。点击股票查看每个门槛、消息原文与五日计划。' : '三个视图来自同一快照；量价对照包含资金条件合格子集，资金缺失单独展示。点击股票查看日 K、原始证据与五日计划。'}
            换手提升是近 5 日与此前 20 日平均换手的倍数；资金占比使用 5 日累计净额 / 累计成交额。
          </p>
          {!ready && candidates.length > 0 ? <p className="mt-2 text-xs text-amber-800">扫描尚未完整完成，以下为当前可读证据，暂不能加入交易复盘。</p> : null}
        </div>
        <FiveDayCandidateTable
          candidates={visibleCandidates}
          assessments={isV2 ? assessments : undefined}
          selectedSymbol={selectedSymbol}
          onSelect={(candidate) => setSelectedSymbol(candidate.symbol)}
          emptyText={!ready ? '等待扫描完成后查看该范围的候选' : view === 'funds' ? isV2 ? '本次没有执行条件齐备的观察股，可切换查看全部主线量价观察与资金缺失' : '本次没有满足资金条件的观察股，可切换查看量价对照与资金缺失' : view === 'missing' ? '本次量价候选没有资金缺失记录' : isV2 ? '本次没有主线量价观察股' : '本次没有资金数据完整的量价候选'}
        />
      </Card>

      <FiveDayHistoryPanel key={scope.strategy} strategy={scope.strategy} snapshotKey={ready ? `${scopeKey}:${snapshot?.signalDate}:${snapshot?.generatedAt}` : ''} />
      <FiveDayRulesPanel strategy={scope.strategy} rules={snapshot?.rules ?? null} ruleVersion={snapshot?.ruleVersion} />

      {selected && snapshot ? (
        <FiveDayDetailOverlay key={`${scopeKey}:${snapshot.signalDate}:${snapshot.generatedAt}:${selected.symbol}`} candidate={selected} snapshot={snapshot} strategy={scope.strategy} onClose={() => setSelectedSymbol(null)} />
      ) : null}
    </div>
  )
}
