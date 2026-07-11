import { useEffect, useMemo, useState } from 'react'
import { Play, Plus, RefreshCw, Star, Trash2 } from 'lucide-react'
import { analyzeWatchlistSymbol, fetchWatchlistHistory } from '../api/client'
import { ScoreBadge, Tag } from '../components/ui/Badge'
import { Button } from '../components/ui/Button'
import { Card } from '../components/ui/Card'
import { Empty } from '../components/ui/Empty'
import { Loader } from '../components/ui/Loader'
import { SectionBanner } from '../components/ui/SectionBanner'
import { toast } from '../components/ui/Toast'
import { extractErrorMessage, formatDateTime, formatNumber } from '../lib/format'
import { useWatchlistStore } from '../store/watchlistStore'
import type { DecisionHistoryEntry, InvestmentDecisionGate, InvestmentDecisionReport, WatchlistEntry } from '../types'

export function WatchlistPage() {
  const entries = useWatchlistStore((state) => state.entries)
  const loading = useWatchlistStore((state) => state.loading)
  const load = useWatchlistStore((state) => state.load)
  const add = useWatchlistStore((state) => state.add)
  const remove = useWatchlistStore((state) => state.remove)
  const [symbol, setSymbol] = useState('')
  const [note, setNote] = useState('')
  const [selectedSymbol, setSelectedSymbol] = useState<string | null>(null)
  const [analysis, setAnalysis] = useState<InvestmentDecisionReport | null>(null)
  const [saving, setSaving] = useState(false)
  const [analyzing, setAnalyzing] = useState(false)
  const [history, setHistory] = useState<DecisionHistoryEntry[]>([])
  const [historyError, setHistoryError] = useState('')

  useEffect(() => {
    void load().catch((error) => toast.error(`特别关注加载失败：${extractErrorMessage(error)}`))
  }, [load])

  useEffect(() => {
    if (!entries.length) {
      setSelectedSymbol(null)
      return
    }
    if (!selectedSymbol || !entries.some((entry) => entry.symbol === selectedSymbol)) {
      setSelectedSymbol(entries[0].symbol)
    }
  }, [entries, selectedSymbol])

  const selected = useMemo(
    () => entries.find((entry) => entry.symbol === selectedSymbol) ?? null,
    [entries, selectedSymbol]
  )

  useEffect(() => {
    if (!selectedSymbol) {
      setHistory([])
      setHistoryError('')
      return
    }
    let alive = true
    setHistoryError('')
    fetchWatchlistHistory(selectedSymbol)
      .then((items) => {
        if (alive) {
          setHistory(items)
          setHistoryError('')
        }
      })
      .catch((error) => {
        if (alive) {
          setHistory([])
          setHistoryError(extractErrorMessage(error))
        }
      })
    return () => {
      alive = false
    }
  }, [selectedSymbol, analysis])

  const submit = async () => {
    const normalized = symbol.trim()
    if (!/^\d{6}$/.test(normalized)) {
      toast.warning('请输入 6 位 A 股代码')
      return
    }
    setSaving(true)
    try {
      const entry = await add(normalized, note.trim())
      setSelectedSymbol(entry.symbol)
      setSymbol('')
      setNote('')
      toast.success(`${entry.companyName} 已加入特别关注`)
    } catch (error) {
      toast.error(`添加失败：${extractErrorMessage(error)}`)
    } finally {
      setSaving(false)
    }
  }

  const runAnalysis = async () => {
    if (!selected) return
    setAnalyzing(true)
    setAnalysis(null)
    try {
      const report = await analyzeWatchlistSymbol(selected.symbol)
      setAnalysis(report)
      await load(true)
      toast.success(`${selected.companyName} 主动分析完成`)
    } catch (error) {
      toast.error(`主动分析失败：${extractErrorMessage(error)}`)
    } finally {
      setAnalyzing(false)
    }
  }

  const removeEntry = async (entry: WatchlistEntry) => {
    try {
      await remove(entry.symbol)
      if (analysis?.symbol === entry.symbol) setAnalysis(null)
      toast.info(`${entry.companyName} 已取消特别关注`)
    } catch (error) {
      toast.error(`取消失败：${extractErrorMessage(error)}`)
    }
  }

  return (
    <div className="flex flex-col gap-4">
      <SectionBanner
        eyebrow="SPECIAL ATTENTION"
        title="特别关注"
        description="独立跟踪你主动选择的股票；关注状态不会进入全市场推荐排名。"
        extra={<Tag tone="warning">不参与推荐排序</Tag>}
      />

      <Card title={<span className="inline-flex items-center gap-2"><Plus className="h-4 w-4 text-brand-500" />新增关注</span>}>
        <div className="grid grid-cols-1 gap-3 md:grid-cols-[180px_minmax(0,1fr)_auto]">
          <label>
            <span className="field-label">股票代码</span>
            <input
              className="field font-mono"
              value={symbol}
              maxLength={6}
              inputMode="numeric"
              placeholder="例如 002714"
              onChange={(event) => setSymbol(event.target.value.replace(/\D/g, ''))}
              onKeyDown={(event) => {
                if (event.key === 'Enter') void submit()
              }}
            />
          </label>
          <label>
            <span className="field-label">关注备注</span>
            <input
              className="field"
              value={note}
              maxLength={1000}
              placeholder="记录核心假设或下一次要核验的变量"
              onChange={(event) => setNote(event.target.value)}
              onKeyDown={(event) => {
                if (event.key === 'Enter') void submit()
              }}
            />
          </label>
          <div className="flex items-end">
            <Button variant="primary" loading={saving} icon={<Star className="h-4 w-4" />} onClick={() => void submit()}>
              特别关注
            </Button>
          </div>
        </div>
      </Card>

      <div className="grid grid-cols-1 gap-4 xl:grid-cols-[380px_minmax(0,1fr)]">
        <Card title="关注列表" flush>
          {loading && !entries.length ? <Loader text="关注列表加载中" /> : null}
          {!loading && !entries.length ? <Empty text="还没有特别关注的股票" /> : null}
          <div className="divide-y divide-line-soft">
            {entries.map((entry) => (
              <div
                key={entry.symbol}
                className={`group flex cursor-pointer items-start gap-3 px-4 py-4 transition hover:bg-amber-50/60 ${selected?.symbol === entry.symbol ? 'bg-amber-50' : 'bg-white'}`}
                role="button"
                tabIndex={0}
                onClick={() => {
                  setSelectedSymbol(entry.symbol)
                  if (analysis?.symbol !== entry.symbol) setAnalysis(null)
                }}
                onKeyDown={(event) => {
                  if (event.key === 'Enter' || event.key === ' ') setSelectedSymbol(entry.symbol)
                }}
              >
                <Star className="mt-0.5 h-4 w-4 shrink-0 fill-amber-400 text-amber-500" />
                <div className="min-w-0 flex-1">
                  <div className="flex flex-wrap items-baseline gap-2">
                    <span className="font-semibold text-ink-900">{entry.companyName}</span>
                    <span className="font-mono text-xs text-ink-400">{entry.symbol}</span>
                  </div>
                  <p className="mt-1 line-clamp-2 text-xs leading-relaxed text-ink-500">{entry.note || '暂无关注备注'}</p>
                  <div className="mt-2 flex flex-wrap items-center gap-2">
                    <Tag tone={entry.lastActionLabel ? 'brand' : 'neutral'}>{entry.lastActionLabel ?? '尚未分析'}</Tag>
                    {entry.lastDecisionScore !== null ? <span className="tabular text-xs text-ink-400">{formatNumber(entry.lastDecisionScore)}</span> : null}
                  </div>
                </div>
                <button
                  type="button"
                  title="取消特别关注"
                  aria-label={`取消特别关注 ${entry.companyName}`}
                  className="inline-flex h-8 w-8 shrink-0 items-center justify-center rounded-lg text-ink-300 opacity-100 transition hover:bg-red-50 hover:text-danger sm:opacity-0 sm:group-hover:opacity-100"
                  onClick={(event) => {
                    event.stopPropagation()
                    void removeEntry(entry)
                  }}
                >
                  <Trash2 className="h-4 w-4" />
                </button>
              </div>
            ))}
          </div>
        </Card>

        <div className="min-w-0">
          {!selected ? <Card><Empty text="选择一只特别关注股票" /></Card> : (
            <Card>
              <div className="flex flex-col gap-4">
                <div className="flex flex-wrap items-start justify-between gap-3 border-b border-line-soft pb-4">
                  <div>
                    <div className="eyebrow">ACTIVE REVIEW · {selected.symbol}</div>
                    <h2 className="mt-1 text-xl font-semibold text-ink-900">{selected.companyName}</h2>
                    <p className="mt-1 text-sm text-ink-500">{selected.note || '暂无关注备注'}</p>
                  </div>
                  <div className="flex items-center gap-2">
                    <Button variant="secondary" icon={<RefreshCw className="h-4 w-4" />} onClick={() => void load(true)}>刷新</Button>
                    <Button variant="primary" loading={analyzing} icon={<Play className="h-4 w-4" />} onClick={() => void runAnalysis()}>
                      主动分析
                    </Button>
                  </div>
                </div>

                {analyzing ? <Loader text="正在核验财报、估值、公告和 Agent 共识" /> : null}
                {!analyzing && !analysis ? (
                  <div className="grid grid-cols-2 gap-3 md:grid-cols-4">
                    <Metric label="最近结论" value={selected.lastActionLabel ?? '尚未分析'} />
                    <Metric label="决策分" value={formatNumber(selected.lastDecisionScore)} />
                    <Metric label="分析时间" value={formatDateTime(selected.lastAnalyzedAt)} />
                    <Metric label="加入时间" value={formatDateTime(selected.createdAt)} />
                  </div>
                ) : null}
                {analysis ? <AnalysisResult report={analysis} /> : null}
                <DecisionHistory items={history} error={historyError} />
              </div>
            </Card>
          )}
        </div>
      </div>
    </div>
  )
}

function DecisionHistory({ items, error }: { items: DecisionHistoryEntry[]; error: string }) {
  return (
    <section className="border-t border-line-soft pt-4">
      <div className="flex items-center justify-between gap-2">
        <h3 className="text-sm font-semibold text-ink-900">决策历史</h3>
        <Tag tone="neutral">{items.length} 次</Tag>
      </div>
      {error ? (
        <div className="mt-3 border-l-2 border-red-300 bg-red-50 px-3 py-2 text-xs leading-relaxed text-danger">
          决策历史加载失败：{error}
        </div>
      ) : items.length ? (
        <div className="mt-3 divide-y divide-line-soft border-y border-line-soft">
          {items.map((item) => (
            <div key={item.decisionId} className="grid grid-cols-[minmax(0,1fr)_auto] gap-3 py-3">
              <div className="min-w-0">
                <div className="flex flex-wrap items-center gap-2">
                  <Tag tone="brand">{item.actionLabel}</Tag>
                  <Tag tone="neutral">{historySourceLabel(item.sourceType)}</Tag>
                  <span className="font-mono text-xs text-ink-400">{item.ruleVersion}</span>
                </div>
                <p className="mt-1 text-xs text-ink-500">数据截至 {formatDateTime(item.dataAsOf)}</p>
              </div>
              <div className="text-right">
                <div className="tabular text-sm font-semibold text-ink-900">{formatNumber(item.decisionScore)}</div>
                <div className="mt-1 text-xs text-ink-400">{formatDateTime(item.recordedAt)}</div>
              </div>
            </div>
          ))}
        </div>
      ) : <p className="mt-2 text-xs text-ink-400">暂无历史决策</p>}
    </section>
  )
}

function historySourceLabel(sourceType: string) {
  if (sourceType === 'SPECIAL_ATTENTION') return '主动分析'
  if (sourceType === 'SHORT_TERM_SCAN') return '短线扫描'
  return sourceType || '系统分析'
}

function AnalysisResult({ report }: { report: InvestmentDecisionReport }) {
  return (
    <div className="flex flex-col gap-4">
      <div className="grid grid-cols-2 gap-3 md:grid-cols-5">
        <Metric label="决策阶段" value={report.actionLabel} />
        <Metric label="决策分" value={<ScoreBadge value={report.decisionScore} />} />
        <Metric label="通过" value={report.passCount} />
        <Metric label="观察" value={report.watchCount} />
        <Metric label="阻断/失败" value={`${report.blockCount}/${report.failCount}`} />
      </div>

      <div className="border-l-2 border-brand-400 pl-4">
        <p className="text-sm font-semibold text-ink-900">{report.actionReason}</p>
        <p className="mt-1 text-xs leading-relaxed text-ink-500">{report.complianceNote}</p>
      </div>

      <div className="grid grid-cols-1 gap-3 md:grid-cols-2">
        {report.gates.map((gate) => <GateRow key={gate.gateCode} gate={gate} />)}
      </div>

      <div className="grid grid-cols-1 gap-4 md:grid-cols-2">
        <ListBlock title="投资逻辑" items={report.thesis} />
        <ListBlock title="买入前提" items={report.buyPreconditions} />
        <ListBlock title="持有纪律" items={report.holdDisciplines} />
        <ListBlock title="待完成动作" items={report.requiredActions} />
      </div>

      <div className="border-t border-line-soft pt-3 text-xs text-ink-400">
        分析完成于 {formatDateTime(report.generatedAt)}
      </div>
    </div>
  )
}

function GateRow({ gate }: { gate: InvestmentDecisionGate }) {
  return (
    <div className="rounded-lg border border-line-soft bg-line-soft/30 p-3">
      <div className="flex items-center justify-between gap-2">
        <span className="text-sm font-semibold text-ink-900">{gate.gateName}</span>
        <Tag tone={gateTone(gate.status)}>{gate.statusLabel}</Tag>
      </div>
      <p className="mt-2 text-xs leading-relaxed text-ink-600">{gate.conclusion}</p>
      <span className="mt-2 block tabular text-xs text-ink-400">分数影响 {gate.scoreImpact > 0 ? '+' : ''}{formatNumber(gate.scoreImpact)}</span>
    </div>
  )
}

function ListBlock({ title, items }: { title: string; items: string[] }) {
  return (
    <section className="border-t border-line-soft pt-3">
      <h3 className="text-sm font-semibold text-ink-900">{title}</h3>
      <ul className="mt-2 flex flex-col gap-1.5 text-xs leading-relaxed text-ink-600">
        {(items.length ? items : ['暂无']).map((item, index) => <li key={`${title}-${index}`}>{item}</li>)}
      </ul>
    </section>
  )
}

function Metric({ label, value }: { label: string; value: React.ReactNode }) {
  return (
    <div className="min-w-0 rounded-lg border border-line-soft bg-white px-3 py-2.5">
      <div className="text-xs text-ink-400">{label}</div>
      <div className="mt-1 min-w-0 break-words text-sm font-semibold text-ink-900">{value}</div>
    </div>
  )
}

function gateTone(status: string): 'success' | 'warning' | 'danger' | 'neutral' {
  if (status === 'PASS') return 'success'
  if (status === 'WATCH') return 'warning'
  if (status === 'BLOCK' || status === 'FAIL') return 'danger'
  return 'neutral'
}
