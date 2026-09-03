import type { ReactNode } from 'react'
import { Bot, CheckCircle2, ShieldAlert, Sparkles } from 'lucide-react'
import {
  changeClass,
  formatAmount,
  formatDateTime,
  formatNumber,
  formatPercent,
  formatSignedPercent
} from '../../lib/format'
import type {
  LeaderAiReview,
  LeaderBarSeries,
  PotentialLeaderCandidate,
  PotentialLeaderDirection,
  PotentialLeaderStatus
} from '../../types'
import { Tag } from '../ui/Badge'
import { Button } from '../ui/Button'
import { DetailOverlay } from '../ui/DetailOverlay'
import { Loader } from '../ui/Loader'
import { LeaderCandlestickChart } from './LeaderCandlestickChart'

interface LeaderDetailOverlayProps {
  candidate: PotentialLeaderCandidate | null
  direction: PotentialLeaderDirection | null
  snapshotStatus: PotentialLeaderStatus | null
  barSeries: LeaderBarSeries | null
  barLoading: boolean
  barError: string | null
  aiReview: LeaderAiReview | null
  aiLoading: boolean
  aiError: string | null
  onRequestAi: () => void
  onClose: () => void
}

export function LeaderDetailOverlay({
  candidate,
  direction,
  snapshotStatus,
  barSeries,
  barLoading,
  barError,
  aiReview,
  aiLoading,
  aiError,
  onRequestAi,
  onClose
}: LeaderDetailOverlayProps) {
  if (!candidate) return null

  const lane = candidate.lane === 'POTENTIAL' ? '潜龙' : '竞龙'
  return (
    <DetailOverlay
      open
      title={`${candidate.name} · ${candidate.symbol}`}
      subtitle={`${lane}第 ${candidate.rank} 名 · ${candidate.industry} · ${candidate.board}`}
      onClose={onClose}
    >
      <div className="space-y-4">
        <section className="grid gap-2 sm:grid-cols-2 lg:grid-cols-5">
          <Fact label="正式等级" value={`${candidate.grade} · ${gradeLabel(candidate.grade)}`} emphasis />
          <Fact label="正式分" value={Number(candidate.score).toFixed(2)} emphasis />
          <Fact label="现价 / 涨幅" value={(
            <span className={changeClass(candidate.changePercent)}>
              {formatNumber(candidate.latestPrice)} / {formatSignedPercent(candidate.changePercent)}
            </span>
          )} />
          <Fact label="成交额 / 流通市值" value={`${formatAmount(candidate.amount)} / ${formatAmount(candidate.circulatingMarketValue)}`} />
          <Fact label="快照指纹" value={`${candidate.snapshotHash.slice(0, 12)}…`} />
        </section>

        <DetailSection title="最近 20 个有效交易日" aside={barSeries ? `${barSeries.adjustMode} 前复权 · 截至 ${barSeries.baselineDate}` : undefined}>
          {barLoading ? <Loader text="日线图加载中" /> : null}
          {barError ? <ErrorBox>{barError}</ErrorBox> : null}
          {barSeries ? <LeaderCandlestickChart bars={barSeries.bars} /> : null}
        </DetailSection>

        <div className="grid gap-4 lg:grid-cols-[1.15fr_0.85fr]">
          <DetailSection title="正式评分分解" aside="前端不复算">
            <div className="overflow-x-auto">
              <table className="w-full min-w-[520px] text-sm">
                <thead className="text-left text-xs text-ink-400">
                  <tr className="border-b border-line-soft">
                    <th className="pb-2 font-medium">分项</th>
                    <th className="pb-2 text-right font-medium">原始值</th>
                    <th className="pb-2 text-right font-medium">得分</th>
                    <th className="pb-2 text-right font-medium">上限</th>
                  </tr>
                </thead>
                <tbody>
                  {candidate.scoreBreakdown.map((component) => (
                    <tr key={component.code} className="border-b border-line-soft/70 last:border-0">
                      <td className="py-2.5 font-medium text-ink-800">{component.label}</td>
                      <td className="tabular py-2.5 text-right text-ink-500">{formatNumber(component.rawValue)}</td>
                      <td className="tabular py-2.5 text-right font-semibold text-brand-600">{formatNumber(component.points)}</td>
                      <td className="tabular py-2.5 text-right text-ink-400">{formatNumber(component.maximumPoints)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </DetailSection>

          <DetailSection title="主线方向事实">
            {direction ? (
              <div className="grid grid-cols-2 gap-2">
                <Fact label="方向排名" value={`#${direction.rank}`} />
                <Fact label="方向得分" value={Number(direction.score).toFixed(2)} />
                <Fact label="上涨广度" value={formatPercent(direction.breadthPercent)} />
                <Fact label="中位涨幅" value={formatSignedPercent(direction.medianChangePercent)} />
                <Fact label="涨停 / 炸板" value={`${direction.limitUpCount} / ${direction.brokenCount}`} />
                <Fact label="最高板" value={`${direction.maxBoard} 板`} />
              </div>
            ) : <p className="text-sm text-ink-400">方向事实待补充</p>}
          </DetailSection>
        </div>

        <div className="grid gap-4 lg:grid-cols-2">
          <DetailSection title="入选证据">
            <EvidenceList items={candidate.evidence} empty="暂无额外入选说明" />
          </DetailSection>
          <DetailSection title="风险扣分" aside={`合计 -${riskTotal(candidate).toFixed(2)} 分`}>
            {candidate.riskDeductions.length ? (
              <div className="space-y-2">
                {candidate.riskDeductions.map((risk) => (
                  <div key={risk.code} className="rounded-lg border border-amber-100 bg-amber-50/60 px-3 py-2">
                    <div className="flex items-center justify-between gap-3 text-sm">
                      <span className="font-medium text-amber-900">{risk.label}</span>
                      <span className="tabular font-semibold text-amber-700">-{formatNumber(risk.points)}</span>
                    </div>
                    <p className="mt-1 text-xs text-amber-700/80">
                      原始值 {formatNumber(risk.rawValue)} · 阈值 {formatNumber(risk.threshold)} · {risk.code}
                    </p>
                  </div>
                ))}
              </div>
            ) : <p className="text-sm text-ink-400">当前没有触发风险扣分项</p>}
          </DetailSection>
        </div>

        <DetailSection
          title="龙虎榜营业部证据"
          aside={`${dragonTigerLabel(candidate.dragonTiger.status)} · 调整 ${signed(candidate.dragonTiger.adjustment)} 分`}
        >
          <div className="mb-3 flex flex-wrap items-center gap-2 text-xs text-ink-500">
            <Tag tone={dragonTigerTone(candidate.dragonTiger.status)}>{dragonTigerLabel(candidate.dragonTiger.status)}</Tag>
            <span>披露日 {candidate.dragonTiger.disclosureDate ?? '—'}</span>
            <span>营业部名称只作原始证据，不映射为特定游资身份。</span>
          </div>
          {candidate.dragonTiger.seats.length ? (
            <div className="overflow-x-auto">
              <table className="w-full min-w-[760px] text-sm">
                <thead className="text-left text-xs text-ink-400">
                  <tr className="border-b border-line-soft">
                    <th className="pb-2 font-medium">方向</th>
                    <th className="pb-2 font-medium">营业部 / 交易单元</th>
                    <th className="pb-2 text-right font-medium">买入</th>
                    <th className="pb-2 text-right font-medium">卖出</th>
                    <th className="pb-2 text-right font-medium">净额</th>
                    <th className="pb-2 text-right font-medium">标记</th>
                  </tr>
                </thead>
                <tbody>
                  {candidate.dragonTiger.seats.map((seat, index) => (
                    <tr key={`${seat.side}:${seat.branchCode ?? seat.branchName}:${index}`} className="border-b border-line-soft/70 last:border-0">
                      <td className="py-2.5"><Tag tone={seat.side === 'BUY' ? 'success' : 'warning'}>{seat.side === 'BUY' ? '买入' : '卖出'}</Tag></td>
                      <td className="py-2.5">
                        <div className="font-medium text-ink-800">{seat.branchName}</div>
                        <div className="tabular mt-0.5 text-xs text-ink-400">{seat.branchCode ?? '代码缺失'}</div>
                      </td>
                      <td className="tabular py-2.5 text-right text-ink-600">{formatAmount(seat.buyAmount)}</td>
                      <td className="tabular py-2.5 text-right text-ink-600">{formatAmount(seat.sellAmount)}</td>
                      <td className="tabular py-2.5 text-right font-medium text-ink-800">{formatAmount(seat.netAmount)}</td>
                      <td className="py-2.5 text-right text-xs text-ink-500">
                        {[seat.institution ? '机构专用' : '', seat.stockConnect ? '股通' : ''].filter(Boolean).join(' · ') || '普通席位'}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          ) : <p className="text-sm text-ink-400">当前没有可展示的营业部明细</p>}
          <EvidenceList items={candidate.dragonTiger.dataGaps} empty="" prefix="数据缺口" />
        </DetailSection>

        <div className="grid gap-4 lg:grid-cols-[0.72fr_1.28fr]">
          <DetailSection title="历史同组校准" aside="影子统计，不改分">
            {candidate.calibration.status === 'COLLECTING' ? (
              <div className="rounded-xl border border-sky-100 bg-sky-50 px-3 py-3 text-sm font-medium text-sky-800">
                样本积累中（{candidate.calibration.sampleSize}/30）
              </div>
            ) : (
              <div className="grid grid-cols-2 gap-2">
                <Fact label="样本数" value={String(candidate.calibration.sampleSize)} />
                <Fact label="成功率" value={formatPercent(candidate.calibration.successRate)} />
                <Fact label="95% 下界" value={formatPercent(candidate.calibration.lower95)} />
                <Fact label="95% 上界" value={formatPercent(candidate.calibration.upper95)} />
              </div>
            )}
          </DetailSection>

          <DetailSection title="AI 影子复核" aside="影子复核，不影响正式排名">
            <div className="flex flex-wrap items-center justify-between gap-3 rounded-xl border border-violet-100 bg-violet-50/50 px-3.5 py-3">
              <div className="flex items-start gap-2">
                <Bot className="mt-0.5 h-4 w-4 shrink-0 text-violet-600" />
                <p className="text-xs leading-relaxed text-violet-800">
                  AI 只读取服务器当前候选、分项、风险和席位证据，不可修改正式分、等级或名次。
                </p>
              </div>
              <Button
                variant="primary"
                icon={<Sparkles className="h-4 w-4" />}
                loading={aiLoading}
                disabled={snapshotStatus !== 'READY'}
                onClick={onRequestAi}
              >
                AI 复核
              </Button>
            </div>
            {snapshotStatus !== 'READY' ? (
              <p className="mt-2 text-xs text-amber-700">只有当前 READY 快照允许发起复核，请先刷新。</p>
            ) : null}
            {aiError ? <div className="mt-3"><ErrorBox>{aiError}</ErrorBox></div> : null}
            {aiReview ? <AiReviewResult review={aiReview} /> : null}
          </DetailSection>
        </div>
      </div>
    </DetailOverlay>
  )
}

function AiReviewResult({ review }: { review: LeaderAiReview }) {
  if (review.status === 'FAILED') {
    return <div className="mt-3"><ErrorBox>{review.errorSummary ?? 'AI 影子复核失败'}</ErrorBox></div>
  }
  return (
    <div className="mt-3 space-y-3 rounded-xl border border-line-soft px-3.5 py-3">
      <div className="flex flex-wrap items-center gap-2">
        <CheckCircle2 className="h-4 w-4 text-success" />
        <Tag tone={review.verdict === 'SUPPORT' ? 'success' : review.verdict === 'OPPOSE' ? 'warning' : 'neutral'}>
          {aiVerdictLabel(review.verdict)}
        </Tag>
        <Tag tone="neutral">{aiPhaseLabel(review.phase)}</Tag>
        <span className="tabular text-xs text-ink-400">
          置信度 {review.confidence === null ? '—' : formatPercent(review.confidence * 100)} · {formatDateTime(review.createdAt)}
        </span>
      </div>
      <div className="grid gap-3 sm:grid-cols-2">
        <ReviewList title="支持证据" items={review.advantages} />
        <ReviewList title="反证" items={review.counterEvidence} />
        <ReviewList title="数据缺口" items={review.dataGaps} />
        <ReviewList title="待人工核实" items={review.mustVerify} />
      </div>
      <p className="text-[11px] text-ink-400">
        {review.provider ?? '模型提供方待补充'} / {review.model ?? '模型待补充'} · {review.promptVersion}
      </p>
    </div>
  )
}

function DetailSection({
  title,
  aside,
  children
}: {
  title: string
  aside?: string
  children: ReactNode
}) {
  return (
    <section className="rounded-xl border border-line-soft bg-white p-4">
      <div className="mb-3 flex flex-wrap items-center justify-between gap-2">
        <h3 className="text-sm font-semibold text-ink-900">{title}</h3>
        {aside ? <span className="tabular text-xs text-ink-400">{aside}</span> : null}
      </div>
      {children}
    </section>
  )
}

function Fact({ label, value, emphasis = false }: { label: string; value: ReactNode; emphasis?: boolean }) {
  return (
    <div className="min-w-0 rounded-xl border border-line-soft bg-line-soft/20 px-3 py-2.5">
      <div className="text-[11px] text-ink-400">{label}</div>
      <div className={`tabular mt-1 break-words ${emphasis ? 'text-lg font-semibold text-brand-600' : 'text-sm font-medium text-ink-800'}`}>
        {value}
      </div>
    </div>
  )
}

function EvidenceList({ items, empty, prefix }: { items: string[]; empty: string; prefix?: string }) {
  if (!items.length) return empty ? <p className="text-sm text-ink-400">{empty}</p> : null
  return (
    <ul className="mt-2 space-y-2 text-sm leading-relaxed text-ink-600">
      {items.map((item, index) => (
        <li key={`${item}-${index}`} className="flex gap-2">
          <span className="mt-2 h-1.5 w-1.5 shrink-0 rounded-full bg-brand-400" />
          <span>{prefix ? `${prefix}：` : ''}{item}</span>
        </li>
      ))}
    </ul>
  )
}

function ReviewList({ title, items }: { title: string; items: string[] }) {
  return (
    <div>
      <div className="mb-1.5 text-xs font-semibold text-ink-700">{title}</div>
      {items.length ? (
        <ul className="space-y-1 text-xs leading-relaxed text-ink-500">
          {items.map((item, index) => <li key={`${item}-${index}`}>· {item}</li>)}
        </ul>
      ) : <span className="text-xs text-ink-400">无</span>}
    </div>
  )
}

function ErrorBox({ children }: { children: ReactNode }) {
  return (
    <div className="flex items-start gap-2 rounded-lg border border-red-200 bg-red-50 p-3 text-sm text-red-700">
      <ShieldAlert className="mt-0.5 h-4 w-4 shrink-0" />
      <span>{children}</span>
    </div>
  )
}

function riskTotal(candidate: PotentialLeaderCandidate) {
  return candidate.riskDeductions.reduce((sum, item) => sum + Number(item.points), 0)
}

function gradeLabel(grade: PotentialLeaderCandidate['grade']) {
  return { A: '领涨候选', B: '竞争候选', C: '观察' }[grade]
}

function dragonTigerLabel(status: PotentialLeaderCandidate['dragonTiger']['status']) {
  return {
    DISCLOSED: '已披露',
    NOT_DISCLOSED: '未披露',
    FETCH_FAILED: '抓取失败',
    STALE_DISCLOSURE: '历史披露'
  }[status]
}

function dragonTigerTone(status: PotentialLeaderCandidate['dragonTiger']['status']): 'success' | 'neutral' | 'warning' {
  return status === 'DISCLOSED' ? 'success' : status === 'FETCH_FAILED' ? 'warning' : 'neutral'
}

function aiVerdictLabel(verdict: LeaderAiReview['verdict']) {
  if (verdict === 'SUPPORT') return '支持正式结论'
  if (verdict === 'OPPOSE') return '提出反证'
  if (verdict === 'NEUTRAL') return '中性复核'
  return '复核结果待补充'
}

function aiPhaseLabel(phase: LeaderAiReview['phase']) {
  if (!phase) return '阶段待补充'
  return {
    EMERGING: '萌芽',
    COMPETING: '竞争',
    CROWDED: '拥挤',
    FADING: '退潮'
  }[phase]
}

function signed(value: number) {
  const number = Number(value)
  return `${number > 0 ? '+' : ''}${number.toFixed(2)}`
}
