import { useEffect, useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import { createFiveDayAttestation } from '../../api/fiveDay'
import {
  extractErrorMessage, formatAmount, formatDateTime, formatNumber, formatPercent, formatSignedPercent
} from '../../lib/format'
import { useTradeFeedbackStore } from '../../store/tradeFeedbackStore'
import type { FiveDayAttestation, FiveDayBar, FiveDayCandidate, FiveDaySnapshot, FiveDayStrategy } from '../../types/fiveDay'
import { LeaderCandlestickChart } from '../leader/LeaderCandlestickChart'
import { BuyEntryButton } from '../tradefeedback/BuyEntryButton'
import { TradeReviewButton } from '../tradefeedback/TradeReviewButton'
import { Tag } from '../ui/Badge'
import { Button } from '../ui/Button'
import { Card } from '../ui/Card'
import { DataTable, type Column } from '../ui/DataTable'
import { DetailOverlay } from '../ui/DetailOverlay'
import { DecisionTag, FiveDayAssessmentPanel } from './FiveDayContextPanel'
import { FiveDayExecutionPanel } from './FiveDayExecutionPanel'
import { flowLabel } from './FiveDayCandidateTable'

interface FiveDayDetailOverlayProps {
  candidate: FiveDayCandidate
  snapshot: FiveDaySnapshot
  strategy?: FiveDayStrategy
  onClose: () => void
}

export function FiveDayDetailOverlay({ candidate, snapshot, strategy = 'v1', onClose }: FiveDayDetailOverlayProps) {
  const [attestation, setAttestation] = useState<FiveDayAttestation | null>(null)
  const [attestationLoading, setAttestationLoading] = useState(false)
  const [attestationError, setAttestationError] = useState<string | null>(null)
  const ensureCase = useTradeFeedbackStore((state) => state.ensureCase)
  const generation = useRef(0)
  const pending = useRef(false)

  useEffect(() => () => { generation.current += 1 }, [])

  const isV2 = strategy === 'v2'
  const assessment = snapshot.context?.assessments.find((item) => item.symbol === candidate.symbol)
  const shanghaiToday = formatDateTime(new Date().toISOString()).slice(0, 10)
  const unavailable = snapshot.status !== 'READY'
    ? '等待本次扫描完成后加入复盘。'
    : !snapshot.forwardEligible
      ? '仅前瞻观察快照可以关联本策略成交记录；当前快照可用于研究证据与历史情景。'
    : isV2 && assessment?.decision !== 'ELIGIBLE'
      ? '仅执行条件齐备的前瞻观察股可从本策略加入交易复盘；其他条件判断只用于研究。'
    : !candidate.flowDataComplete || !candidate.matchesFundFlow
      ? '仅资金条件合格的观察股可从本策略加入交易复盘。'
      : !candidate.entryDate
        ? '交易日历尚未核实，暂不能创建复盘凭据。'
        : isV2 && candidate.entryDate > shanghaiToday
          ? '计划入场日尚未到来，等待入场日核实执行条件后再加入交易复盘。'
        : candidate.entryDate < shanghaiToday
          ? '该信号的计划入场日已过去；仍可查看研究证据与历史情景。'
          : null

  const joinReview = async () => {
    if (unavailable || pending.current) return
    pending.current = true
    const current = ++generation.current
    setAttestationLoading(true)
    setAttestationError(null)
    try {
      const token = await createFiveDayAttestation(candidate.symbol, {
        strategy,
        includeGrowth: snapshot.includeGrowth,
        includeStar: snapshot.includeStar
      })
      if (current !== generation.current) return
      await ensureCase({ attestationToken: token.attestationToken })
      if (current === generation.current) setAttestation(token)
    } catch (error) {
      if (current === generation.current) setAttestationError(extractErrorMessage(error))
    } finally {
      if (current === generation.current) {
        pending.current = false
        setAttestationLoading(false)
      }
    }
  }

  const barColumns: Column<FiveDayBar>[] = [
    { key: 'tradeDate', title: '交易日' },
    { key: 'open', title: '开盘', render: (bar) => formatNumber(bar.open) },
    { key: 'high', title: '最高', render: (bar) => formatNumber(bar.high) },
    { key: 'low', title: '最低', render: (bar) => formatNumber(bar.low) },
    { key: 'close', title: '收盘', render: (bar) => formatNumber(bar.close) },
    { key: 'volume', title: '成交量（原始值）', render: (bar) => formatNumber(bar.volume) },
    { key: 'amount', title: '成交额', render: (bar) => formatAmount(bar.amount) },
    { key: 'turnoverRate', title: '换手率', render: (bar) => formatPercent(bar.turnoverRate) }
  ]

  const sourceUrl = safeSourceUrl(candidate.flowSourceUrl)
  return (
    <DetailOverlay
      open
      title={`${candidate.name} · ${candidate.symbol}`}
      subtitle={`信号日 ${snapshot.signalDate || '待核实'} · ${candidate.industry || candidate.board} · ${snapshot.ruleVersion}`}
      onClose={onClose}
    >
      <div className="flex flex-col gap-4">
        <div className="flex flex-wrap items-center gap-2">
          {isV2 ? <DecisionTag decision={assessment?.decision} /> : null}
          <Tag tone={candidate.matchesFundFlow ? 'success' : candidate.flowDataComplete ? 'neutral' : 'warning'}>{flowLabel(candidate)}</Tag>
          <Tag tone={snapshot.forwardEligible ? 'brand' : 'neutral'}>{snapshot.forwardEligible ? '前瞻样本' : '不纳入前瞻统计'}</Tag>
          <span className="text-xs text-ink-500">快照 {formatDateTime(snapshot.generatedAt)}</span>
        </div>
        {isV2 ? <FiveDayAssessmentPanel assessment={assessment} /> : null}
        <Card title="五交易日计划">
          <div className="grid grid-cols-2 gap-4 text-sm md:grid-cols-4">
            <Metric title="参考失效价" value={formatNumber(candidate.stopPrice)} />
            <Metric title="研究入场上限" value={formatNumber(candidate.maxEntryPrice)} />
            <Metric title="计划入场 · 第 1 日" value={candidate.entryDate || '日历待核实'} />
            <Metric title="计划退出 · 第 5 日" value={candidate.plannedExitDate || '日历待核实'} />
          </div>
          <p className="mt-4 text-xs leading-6 text-ink-500">
            以信号次交易日开盘为日线情景基准，买入日计第 1 日。开盘高于入场上限、低于或等于失效价或在涨停附近时，不假设成交。
            固定情景为第 5 日收盘退出；风险情景为此前收盘跌破失效价后，下一个可卖出交易日开盘退出，最迟仍按第 5 日收盘计划退出。
            卖出时跌停附近、零成交量等无法确认成交的情况保持待完成或延期；这些价格均为研究参考。
            初始价差上限只用于入场筛选，不是最大亏损保证，收盘触发后的下一日开盘可能越过失效位。
          </p>
        </Card>
        <Card title="证据数值">
          <div className="grid grid-cols-2 gap-4 text-sm md:grid-cols-3">
            <Metric title="信号日收盘" value={formatNumber(candidate.signalClose)} />
            <Metric title="10 日横盘范围" value={`${formatNumber(candidate.rangeLow)}–${formatNumber(candidate.rangeHigh)}`} />
            <Metric title="区间振幅" value={formatPercent(candidate.rangePercent)} />
            <Metric title="首尾收盘变化" value={formatSignedPercent(candidate.priceChangePercent)} />
            <Metric title="前后 5 日均价变化" value={formatSignedPercent(candidate.centerChangePercent)} />
            <Metric title="换手提升（近 5 日 / 此前 20 日）" value={`${formatNumber(candidate.turnoverRatio)} 倍`} />
            <Metric title="此前 20 日 / 近 5 日平均换手" value={`${formatPercent(candidate.priorTurnover)} / ${formatPercent(candidate.recentTurnover)}`} />
            <Metric title="近 5 日日均成交额" value={formatAmount(candidate.averageAmount)} />
            <Metric title="5 日累计主力净额" value={candidate.flowDataComplete ? formatAmount(candidate.mainNetInflow) : '资金缺失'} />
            <Metric title="累计净额 / 累计成交额" value={candidate.flowDataComplete ? formatPercent(candidate.mainNetInflowRatio) : '资金缺失'} />
            <Metric title="主力净额为正天数" value={candidate.flowDataComplete ? `${candidate.positiveFlowDays} / 5 日` : '资金缺失'} />
          </div>
          {candidate.evidence.length > 0 ? (
            <ul className="mt-4 list-disc space-y-1 border-t border-line-soft pl-5 pt-4 text-xs leading-6 text-ink-600">
              {candidate.evidence.map((evidence, index) => <li key={index}>{evidence}</li>)}
            </ul>
          ) : null}
          <p className="mt-3 text-xs leading-6 text-ink-500">
            东方财富按大单分类的主力净流入，不识别真实账户，不等于“主力吸筹”。
            {sourceUrl ? <a className="ml-1 text-brand-600 underline" href={sourceUrl} target="_blank" rel="noreferrer">查看资金原始来源</a> : '当前没有可用的资金来源链接。'}
          </p>
        </Card>
        {candidate.dataGaps.length > 0 ? (
          <Card title="数据缺失" className="border-amber-200 bg-amber-50">
            <ul className="list-disc space-y-1 pl-5 text-xs leading-6 text-amber-800">
              {candidate.dataGaps.map((gap, index) => <li key={index}>{gap}</li>)}
            </ul>
          </Card>
        ) : null}
        <Card title={`截至信号日的日 K · ${candidate.bars.length} 个交易日`}>
          <LeaderCandlestickChart bars={candidate.bars} />
        </Card>
        <Card title={`日 K 原始证据 · ${candidate.bars.length} 条`}>
          <details>
            <summary className="cursor-pointer text-sm text-brand-700">展开日期、价格、成交额与换手率</summary>
            <div className="mt-3">
              <DataTable columns={barColumns} data={candidate.bars} rowKey={(bar) => bar.tradeDate} tableClassName="min-w-[880px]" emptyText="日 K 数据缺失" />
            </div>
          </details>
        </Card>
        {isV2 ? <FiveDayExecutionPanel symbol={candidate.symbol} entryDate={candidate.entryDate} scope={{ strategy, includeGrowth: snapshot.includeGrowth, includeStar: snapshot.includeStar }} /> : null}
        <Card title="真实成交复盘">
          <p className="mb-3 text-sm leading-6 text-ink-600">
            加入复盘只建立观察记录；“买入”用于录入你已经发生的实际成交，不连接券商，也不会下单。
            请填写实际成交价与时间；真实五日计划从账本首次买入日计算。
            {isV2 ? '申请加入复盘时服务端会重新检查当日执行条件，仅通过后才签发凭据；冻结条件齐备不等同当日可执行。' : null}
          </p>
          {unavailable ? <p className="text-xs text-ink-500">{unavailable}</p> : attestation ? (
            <div className="flex flex-wrap items-center gap-3">
              <TradeReviewButton symbol={candidate.symbol} sourceModule="FIVE_DAY" ruleVersion={snapshot.ruleVersion} recommendedAt={attestation.recommendedAt} attestationToken={attestation.attestationToken} />
              <span className="text-sm text-ink-600">已加入交易复盘</span>
              <BuyEntryButton symbol={candidate.symbol} companyName={candidate.name} latestPrice={null} recommendedAt={attestation.recommendedAt} attestationToken={attestation.attestationToken} />
              <Link className="text-sm text-brand-600 underline" to="/trade-review">查看交易复盘</Link>
            </div>
          ) : (
            <Button type="button" variant="primary" loading={attestationLoading} onClick={() => void joinReview()}>加入交易复盘</Button>
          )}
          {attestationError ? <p role="alert" className="mt-3 text-sm text-red-700">{attestationError}</p> : null}
        </Card>
      </div>
    </DetailOverlay>
  )
}

function Metric({ title, value }: { title: string; value: string }) {
  return <div><div className="text-xs text-ink-500">{title}</div><div className="tabular mt-1 font-semibold text-ink-900">{value}</div></div>
}

function safeSourceUrl(value: string | null) {
  if (!value) return null
  try {
    const url = new URL(value)
    return url.protocol === 'https:' || url.protocol === 'http:' ? url.href : null
  } catch {
    return null
  }
}
