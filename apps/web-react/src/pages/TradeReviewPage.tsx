import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState, type ButtonHTMLAttributes, type FormEvent, type KeyboardEvent, type ReactNode } from 'react'
import { Edit3, Plus, RefreshCw, RotateCcw, Trash2, X } from 'lucide-react'
import {
  addTradeFill,
  cancelTradeCase,
  deleteTradeCase,
  deleteTradeFill,
  refreshTradeCase,
  recordManualTradeFill,
  updateTradeFill
} from '../api/client'
import { Tag } from '../components/ui/Badge'
import { Button } from '../components/ui/Button'
import { DataTable, type Column } from '../components/ui/DataTable'
import { DetailOverlay, acquireBodyScrollLock } from '../components/ui/DetailOverlay'
import { Loader } from '../components/ui/Loader'
import { toast } from '../components/ui/Toast'
import { changeClass, extractErrorMessage, formatDateTime, formatNumber, formatSignedPercent } from '../lib/format'
import {
  extractTradeMutationError,
  formatShanghaiDateTimeLocal,
  parseShanghaiDateTimeLocal,
  shouldApplySelectedCaseOperation,
  type CaseOperation
} from '../lib/tradeReview'
import { useTradeFeedbackStore } from '../store/tradeFeedbackStore'
import type {
  ManualTradeFillRequest,
  TradeCaseDetail,
  TradeCaseStatus,
  TradeCaseSummary,
  TradeFillView,
  TradeOutcomeView,
  TradeSide,
  UpsertTradeFillRequest
} from '../types'

type StatusFilter = 'ALL' | TradeCaseStatus
type TradeCase = TradeCaseSummary | TradeCaseDetail
type CaseMutationKind = 'refresh' | 'cancel' | 'delete' | 'save'
type CaseMutation = CaseOperation & { kind: CaseMutationKind }

const statusTabs: { value: StatusFilter; label: string }[] = [
  { value: 'ALL', label: '全部' },
  { value: 'PLANNED', label: '计划中' },
  { value: 'HOLDING', label: '持仓中' },
  { value: 'CLOSED', label: '已了结' },
  { value: 'CANCELLED', label: '已取消' }
]

const statusMeta: Record<TradeCaseStatus, { label: string; tone: 'brand' | 'success' | 'neutral' | 'danger' }> = {
  PLANNED: { label: '计划中', tone: 'brand' },
  HOLDING: { label: '持仓中', tone: 'success' },
  CLOSED: { label: '已了结', tone: 'neutral' },
  CANCELLED: { label: '已取消', tone: 'danger' }
}

const statusRank: Record<TradeCaseStatus, number> = { HOLDING: 0, CLOSED: 1, PLANNED: 2, CANCELLED: 3 }

const outcomeMeta: Record<string, { label: string; tone: 'sky' | 'success' | 'danger' | 'neutral' }> = {
  PENDING: { label: 'PENDING', tone: 'sky' },
  MATURED: { label: 'MATURED', tone: 'success' },
  UNAVAILABLE: { label: 'UNAVAILABLE', tone: 'danger' }
}

export function TradeReviewPage() {
  const casesById = useTradeFeedbackStore((state) => state.casesById)
  const loaded = useTradeFeedbackStore((state) => state.loaded)
  const loading = useTradeFeedbackStore((state) => state.loading)
  const loadingMore = useTradeFeedbackStore((state) => state.loadingMore)
  const hasMore = useTradeFeedbackStore((state) => state.hasMore)
  const loadCases = useTradeFeedbackStore((state) => state.loadCases)
  const loadMoreCases = useTradeFeedbackStore((state) => state.loadMoreCases)
  const refreshCases = useTradeFeedbackStore((state) => state.refreshCases)
  const getCase = useTradeFeedbackStore((state) => state.getCase)
  const upsertCase = useTradeFeedbackStore((state) => state.upsertCase)
  const removeCase = useTradeFeedbackStore((state) => state.removeCase)
  const [filter, setFilter] = useState<StatusFilter>('ALL')
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const [listError, setListError] = useState('')
  const [detailErrors, setDetailErrors] = useState<Record<string, string>>({})
  const [detailLoadingId, setDetailLoadingId] = useState<string | null>(null)
  const [mutation, setMutation] = useState<CaseMutation | null>(null)
  const [fillModal, setFillModal] = useState<{ fill?: TradeFillView; returnFocus: HTMLElement | null } | null>(null)
  const [manualModal, setManualModal] = useState<{ returnFocus: HTMLElement | null } | null>(null)
  const [manualSaving, setManualSaving] = useState(false)
  const [cancelDialog, setCancelDialog] = useState<{ caseId: string; symbol: string; returnFocus: HTMLElement | null } | null>(null)
  const mutationSequenceRef = useRef(0)
  const activeMutationIdRef = useRef<number | null>(null)
  const selectedIdRef = useRef<string | null>(null)

  const allCases = useMemo(
    () => Object.values(casesById).sort((left, right) => {
      const rankGap = statusRank[left.status] - statusRank[right.status]
      if (rankGap !== 0) return rankGap
      if (left.status === 'HOLDING') return positionOpenedTime(left) - positionOpenedTime(right)
      return Date.parse(right.updatedAt) - Date.parse(left.updatedAt)
    }),
    [casesById]
  )
  const filteredCases = useMemo(
    () => filter === 'ALL' ? allCases : allCases.filter((tradeCase) => tradeCase.status === filter),
    [allCases, filter]
  )
  const selected = selectedId ? casesById[selectedId] : undefined
  const detailError = selectedId ? detailErrors[selectedId] ?? '' : ''
  const clearDetailError = useCallback((caseId: string) => {
    setDetailErrors((current) => {
      if (!(caseId in current)) return current
      const { [caseId]: _removed, ...remaining } = current
      return remaining
    })
  }, [])
  const isCurrentSelectedOperation = useCallback((operation: CaseOperation) => (
    shouldApplySelectedCaseOperation(operation, mutationSequenceRef.current, selectedIdRef.current)
  ), [])
  const runSelectedMutation = useCallback(async <T,>(caseId: string, kind: CaseMutationKind, request: () => Promise<T>) => {
    if (activeMutationIdRef.current !== null) return undefined
    const operation: CaseMutation = { id: ++mutationSequenceRef.current, caseId, kind }
    activeMutationIdRef.current = operation.id
    setMutation(operation)
    try {
      return { operation, response: await request() }
    } catch (error) {
      return { operation, error }
    } finally {
      if (activeMutationIdRef.current === operation.id) {
        activeMutationIdRef.current = null
        setMutation((current) => current?.id === operation.id ? null : current)
      }
    }
  }, [])

  useEffect(() => {
    void loadCases().catch((error) => {
      const message = extractErrorMessage(error)
      setListError(message)
      toast.error(`复盘单加载失败：${message}`)
    })
  }, [loadCases])

  useEffect(() => {
    if (selectedId && !filteredCases.some((tradeCase) => tradeCase.caseId === selectedId)) {
      setSelectedId(null)
    }
  }, [filteredCases, selectedId])

  useLayoutEffect(() => {
    selectedIdRef.current = selectedId
    if (selectedId) clearDetailError(selectedId)
  }, [clearDetailError, selectedId])

  useEffect(() => {
    if (!selectedId || isTradeCaseDetail(casesById[selectedId])) return
    let alive = true
    setDetailLoadingId(selectedId)
    clearDetailError(selectedId)
    void getCase(selectedId)
      .catch((error) => {
        if (!alive) return
        const message = extractErrorMessage(error)
        setDetailErrors((current) => ({ ...current, [selectedId]: message }))
        toast.error(`复盘详情加载失败：${message}`)
      })
      .finally(() => {
        if (alive) setDetailLoadingId((current) => current === selectedId ? null : current)
      })
    return () => {
      alive = false
    }
  }, [casesById, clearDetailError, getCase, selectedId])

  const counts = useMemo(() => {
    const next: Record<StatusFilter, number> = { ALL: allCases.length, PLANNED: 0, HOLDING: 0, CLOSED: 0, CANCELLED: 0 }
    allCases.forEach((tradeCase) => { next[tradeCase.status] += 1 })
    return next
  }, [allCases])

  const profitTotals = useMemo(() => {
    let total = 0
    let hasTotal = false
    let realized = 0
    let unrealized = 0
    let hasUnrealized = false
    filteredCases.forEach((tradeCase) => {
      const ledger = tradeCase.ledger
      if (ledger.totalProfit !== null) {
        total += ledger.totalProfit
        hasTotal = true
      }
      if (ledger.realizedProfit !== null) realized += ledger.realizedProfit
      if (ledger.unrealizedProfit !== null) {
        unrealized += ledger.unrealizedProfit
        hasUnrealized = true
      }
    })
    return {
      total: hasTotal ? total : null,
      realized,
      unrealized: hasUnrealized ? unrealized : null
    }
  }, [filteredCases])

  const selectCase = useCallback((caseId: string) => {
    selectedIdRef.current = caseId
    setSelectedId(caseId)
  }, [])

  const removeReviewCase = useCallback(async (tradeCase: TradeCase) => {
    if (activeMutationIdRef.current !== null || !canDeleteReviewCase(tradeCase)) return
    if (!window.confirm(`确认删除 ${tradeCase.symbol} ${tradeCase.companyName} 的复盘关注？仅未成交计划或已取消关注可以删除。`)) return
    clearDetailError(tradeCase.caseId)
    const result = await runSelectedMutation(tradeCase.caseId, 'delete', () => deleteTradeCase(tradeCase.caseId))
    if (!result) return
    if ('error' in result) {
      const message = extractTradeMutationError(result.error)
      if (selectedIdRef.current === tradeCase.caseId) {
        setDetailErrors((current) => ({ ...current, [tradeCase.caseId]: message }))
      }
      toast.error(`删除关注失败：${message}`)
      return
    }
    removeCase(tradeCase.caseId)
    if (selectedIdRef.current === tradeCase.caseId) {
      selectedIdRef.current = null
      setSelectedId(null)
    }
    toast.success('复盘关注已删除')
  }, [clearDetailError, removeCase, runSelectedMutation])

  const columns = useMemo<Column<TradeCase>[]>(() => [
    {
      key: 'identity',
      title: '股票 / 状态',
      width: '170px',
      render: (tradeCase) => (
        <button
          type="button"
          aria-pressed={tradeCase.caseId === selectedId}
          className="group min-w-[145px] rounded-md text-left outline-none focus-visible:ring-2 focus-visible:ring-brand-300"
          onClick={(event) => {
            event.stopPropagation()
            selectCase(tradeCase.caseId)
          }}
        >
          <span className="block font-semibold text-ink-900 group-hover:text-brand-600">{tradeCase.symbol}</span>
          <span className="mt-0.5 block max-w-[145px] truncate text-xs text-ink-500">{tradeCase.companyName}</span>
          <StatusTag status={tradeCase.status} className="mt-1.5" />
        </button>
      )
    },
    {
      key: 'source',
      title: '来源',
      width: '130px',
      render: (tradeCase) => (
        <div className="min-w-[110px]">
          <div className="font-medium text-ink-900">{tradeCase.sourceModule}</div>
          <div className="mt-1 text-xs text-ink-400">{tradeCase.ruleVersion}</div>
        </div>
      )
    },
    {
      key: 'recommendation',
      title: '推荐',
      width: '170px',
      render: (tradeCase) => (
        <div className="min-w-[150px]">
          <div className="font-medium text-ink-900">{tradeCase.recommendationAction}</div>
          <div className="mt-1 text-xs text-ink-500">{formatDateTime(tradeCase.recommendedAt)}</div>
          <div className="mt-1 tabular text-xs text-ink-500">{formatMoney(tradeCase.recommendedPrice)}</div>
        </div>
      )
    },
    { key: 'position', title: '持仓', align: 'right', render: (tradeCase) => <NumberCell value={tradeCase.ledger.positionQuantity} suffix=" 股" /> },
    { key: 'cost', title: '加权成本', align: 'right', render: (tradeCase) => <NumberCell value={tradeCase.ledger.averageCost} /> },
    { key: 'latest', title: '最新价', align: 'right', render: (tradeCase) => <NumberCell value={tradeCase.ledger.latestPrice} /> },
    {
      key: 'profit',
      title: '总毛收益',
      align: 'right',
      render: (tradeCase) => <ProfitText value={tradeCase.ledger.totalProfit} />
    },
    ...(['T1', 'T5', 'T20'] as const).map<Column<TradeCase>>((horizon) => ({
      key: horizon,
      title: horizon,
      align: 'right',
      render: (tradeCase) => <OutcomeCell outcome={getRecommendationOutcome(tradeCase, horizon)} />
    })),
    {
      key: 'actions',
      title: '操作',
      align: 'right',
      width: '68px',
      render: (tradeCase) => (
        <IconButton
          label={`删除关注 ${tradeCase.symbol}`}
          disabled={mutation !== null || !canDeleteReviewCase(tradeCase)}
          danger
          onClick={(event) => {
            event.stopPropagation()
            void removeReviewCase(tradeCase)
          }}
          icon={<Trash2 className="h-4 w-4" />}
        />
      )
    }
  ], [mutation, removeReviewCase, selectCase, selectedId])

  const refreshList = async () => {
    setListError('')
    try {
      await refreshCases()
      toast.success('复盘列表已刷新')
    } catch (error) {
      const message = extractErrorMessage(error)
      setListError(message)
      toast.error(`复盘列表刷新失败：${message}`)
    }
  }

  const runCaseAction = async (kind: 'refresh' | 'cancel', caseId: string) => {
    if (activeMutationIdRef.current !== null) return
    clearDetailError(caseId)
    const result = await runSelectedMutation(caseId, kind, () => kind === 'refresh'
      ? refreshTradeCase(caseId)
      : cancelTradeCase(caseId))
    if (!result) return
    if ('error' in result) {
      if (!isCurrentSelectedOperation(result.operation)) return
      const message = extractTradeMutationError(result.error)
      setDetailErrors((current) => ({ ...current, [caseId]: message }))
      toast.error(`${kind === 'refresh' ? '刷新' : '取消'}失败：${message}`)
      return
    }
    upsertCase(result.response)
    if (isCurrentSelectedOperation(result.operation)) {
      toast.success(kind === 'refresh' ? '后续表现已刷新' : '复盘计划已取消')
    }
  }

  const removeFill = async (fill: TradeFillView) => {
    if (!selected || activeMutationIdRef.current !== null || !window.confirm(`确认删除这笔${fill.side === 'BUY' ? '买入' : '卖出'} ${fill.quantity} 股的成交记录？`)) return
    const caseId = selected.caseId
    clearDetailError(caseId)
    const result = await runSelectedMutation(caseId, 'delete', () => deleteTradeFill(caseId, fill.fillId))
    if (!result) return
    if ('error' in result) {
      if (!isCurrentSelectedOperation(result.operation)) return
      const message = extractTradeMutationError(result.error)
      setDetailErrors((current) => ({ ...current, [caseId]: message }))
      toast.error(`删除失败：${message}`)
      return
    }
    upsertCase(result.response)
    if (isCurrentSelectedOperation(result.operation)) toast.success('成交记录已删除')
  }

  const saveFill = async (caseId: string, fillId: string | undefined, request: UpsertTradeFillRequest) => {
    const result = await runSelectedMutation(caseId, 'save', () => fillId
      ? updateTradeFill(caseId, fillId, request)
      : addTradeFill(caseId, request))
    if (!result) return undefined
    if ('error' in result) throw result.error
    upsertCase(result.response)
    return result.response
  }

  const saveManualFill = async (request: ManualTradeFillRequest) => {
    if (manualSaving) return undefined
    setManualSaving(true)
    try {
      const response = await recordManualTradeFill(request)
      upsertCase(response)
      selectedIdRef.current = response.caseId
      setSelectedId(response.caseId)
      return response
    } finally {
      setManualSaving(false)
    }
  }

  return (
    <div className="flex min-w-0 flex-col gap-4">
      <header className="border-b border-line pb-4">
        <div className="eyebrow">TRADE REVIEW</div>
        <div className="mt-1 flex flex-wrap items-end justify-between gap-3">
          <div>
            <h1 className="text-2xl font-semibold text-ink-900">交易复盘</h1>
            <p className="mt-1 text-sm text-ink-500">连接推荐现场、真实分批成交与后续策略表现。</p>
          </div>
          <div className="flex flex-wrap gap-2">
            <Button
              type="button"
              variant="primary"
              aria-label="独立录入成交"
              icon={<Plus className="h-4 w-4" />}
              onClick={(event) => setManualModal({ returnFocus: event.currentTarget })}
            >
              独立录入
            </Button>
            <Button
              type="button"
              variant="secondary"
              loading={loading}
              icon={<RefreshCw className="h-4 w-4" />}
              onClick={() => void refreshList()}
            >
              刷新列表
            </Button>
          </div>
          {loaded && loading ? <span role="status" className="sr-only">复盘列表刷新中</span> : null}
        </div>
      </header>

      <section aria-labelledby="trade-cases-heading" className="min-w-0 border-y border-line">
          <div className="flex flex-wrap items-center justify-between gap-3 border-b border-line-soft py-3">
            <h2 id="trade-cases-heading" className="section-title">复盘单</h2>
            <div className="trade-review-tabs" role="group" aria-label="按复盘状态筛选">
              {statusTabs.map((tab) => (
                <button
                  key={tab.value}
                  type="button"
                  aria-pressed={filter === tab.value}
                  className={filter === tab.value ? 'trade-review-tab trade-review-tab-active' : 'trade-review-tab'}
                  onClick={() => setFilter(tab.value)}
                >
                  {tab.label} <span className="tabular text-xs">{counts[tab.value]}</span>
                </button>
              ))}
            </div>
          </div>
          {listError ? <div role="alert" className="border-b border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">{listError}</div> : null}
          {filteredCases.length ? (
            <div className="flex flex-wrap items-center justify-between gap-x-4 gap-y-1 border-b border-line-soft px-4 py-2.5">
              <span className="text-sm font-medium text-ink-700">
                总计盈亏
                <span className="ml-2 text-xs font-normal text-ink-400">{filteredCases.length} 笔复盘单毛收益累加</span>
              </span>
              <span className="flex flex-wrap items-baseline gap-x-3 gap-y-0.5">
                <span className={`tabular text-base font-semibold ${changeClass(profitTotals.total)}`}>{formatSignedMoney(profitTotals.total)}</span>
                <span className="tabular text-xs text-ink-500">
                  已实现 <span className={changeClass(profitTotals.realized)}>{formatSignedMoney(profitTotals.realized)}</span>
                  <span className="mx-1.5 text-ink-300">·</span>
                  浮动 <span className={changeClass(profitTotals.unrealized)}>{profitTotals.unrealized === null ? '—' : formatSignedMoney(profitTotals.unrealized)}</span>
                </span>
              </span>
            </div>
          ) : null}
          {!loaded && loading ? (
            <div role="status"><Loader text="复盘单加载中" className="py-14" /></div>
          ) : (
            <DataTable
              columns={columns}
              data={filteredCases}
              rowKey={(tradeCase) => tradeCase.caseId}
              tableClassName="trade-review-table"
              isSelected={(tradeCase) => tradeCase.caseId === selectedId}
              onRowClick={(tradeCase) => selectCase(tradeCase.caseId)}
              emptyText={filter === 'ALL' ? '暂无复盘单' : '当前状态下暂无复盘单'}
            />
          )}
          {loaded && hasMore ? (
            <div className="flex justify-center border-t border-line-soft py-3">
              <Button
                type="button"
                variant="secondary"
                loading={loadingMore}
                onClick={() => void loadMoreCases().catch((error) => {
                  const message = extractErrorMessage(error)
                  setListError(message)
                  toast.error(`更早复盘单加载失败：${message}`)
                })}
              >
                加载更早记录
              </Button>
            </div>
          ) : null}
      </section>

      <DetailOverlay
        open={selectedId !== null}
        title={selected ? `${selected.symbol} ${selected.companyName}` : '交易复盘详情'}
        subtitle={selected ? `${selected.sourceModule} · ${selected.ruleVersion}` : '加载复盘记录'}
        onClose={() => setSelectedId(null)}
      >
        {!selected ? (
          <div role="status"><Loader text="详情加载中" className="py-12" /></div>
        ) : detailLoadingId === selected.caseId && !isTradeCaseDetail(selected) ? (
          <div role="status"><Loader text="详情加载中" className="py-12" /></div>
        ) : isTradeCaseDetail(selected) ? (
          <CaseDetail
            tradeCase={selected}
            error={detailError}
            action={mutation?.kind ?? null}
            busy={mutation !== null}
            onAddFill={(returnFocus) => { if (!mutation) setFillModal({ returnFocus }) }}
            onEditFill={(fill, returnFocus) => { if (!mutation) setFillModal({ fill, returnFocus }) }}
            onDeleteFill={(fill) => void removeFill(fill)}
            onRefresh={() => void runCaseAction('refresh', selected.caseId)}
            onRequestCancel={(returnFocus) => setCancelDialog({ caseId: selected.caseId, symbol: selected.symbol, returnFocus })}
          />
        ) : (
          <div role="alert" className="border border-red-200 bg-red-50 px-3 py-3 text-sm text-red-700">
            {detailError || '复盘详情暂时不可用'}
          </div>
        )}
      </DetailOverlay>

      {fillModal && selected && isTradeCaseDetail(selected) ? (
        <FillModal
          tradeCase={selected}
          fill={fillModal.fill}
          returnFocus={fillModal.returnFocus}
          busy={mutation !== null}
          onClose={() => setFillModal(null)}
          onSubmit={(request) => saveFill(selected.caseId, fillModal.fill?.fillId, request)}
          onSaved={() => {
            setFillModal(null)
          }}
        />
      ) : null}

      {manualModal ? (
        <ManualTradeModal
          returnFocus={manualModal.returnFocus}
          busy={manualSaving}
          onClose={() => setManualModal(null)}
          onSubmit={saveManualFill}
          onSaved={() => {
            setManualModal(null)
            toast.success('手工成交已记录')
          }}
        />
      ) : null}

      {cancelDialog ? (
        <CancelPlanDialog
          symbol={cancelDialog.symbol}
          returnFocus={cancelDialog.returnFocus}
          busy={mutation !== null}
          onClose={() => setCancelDialog(null)}
          onConfirm={() => {
            const pendingCancel = cancelDialog
            setCancelDialog(null)
            void runCaseAction('cancel', pendingCancel.caseId)
          }}
        />
      ) : null}
    </div>
  )
}

function CaseDetail({
  tradeCase,
  error,
  action,
  busy,
  onAddFill,
  onEditFill,
  onDeleteFill,
  onRefresh,
  onRequestCancel
}: {
  tradeCase: TradeCaseDetail
  error: string
  action: CaseMutationKind | null
  busy: boolean
  onAddFill: (returnFocus: HTMLElement | null) => void
  onEditFill: (fill: TradeFillView, returnFocus: HTMLElement | null) => void
  onDeleteFill: (fill: TradeFillView) => void
  onRefresh: () => void
  onRequestCancel: (returnFocus: HTMLElement | null) => void
}) {
  const recommendationOutcomes = ['T1', 'T5', 'T20'].map((horizon) => getRecommendationOutcome(tradeCase, horizon))
  const executionOutcomes = tradeCase.outcomes.filter((outcome) => outcome.baselineType === 'EXECUTION')
  const firstBuy = [...tradeCase.fills]
    .filter((fill) => fill.side === 'BUY')
    .sort((left, right) => Date.parse(left.executedAt) - Date.parse(right.executedAt))[0]
  const executionDeviation = firstBuy && tradeCase.recommendedPrice > 0
    ? ((firstBuy.price / tradeCase.recommendedPrice) - 1) * 100
    : null

  return (
    <div className="border-y border-line bg-white">
      <div className="border-b border-line px-4 py-4">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <div className="flex flex-wrap items-center gap-2">
            <StatusTag status={tradeCase.status} />
            <Tag tone={tradeCase.recommendationVerified ? 'success' : 'neutral'}>
              {tradeCase.recommendationVerified ? '系统认证' : '历史未认证'}
            </Tag>
          </div>
          <div className="flex flex-wrap gap-2">
            <IconButton label="刷新后续表现" loading={action === 'refresh'} disabled={busy} onClick={onRefresh} icon={<RotateCcw className="h-4 w-4" />} />
            {tradeCase.status === 'PLANNED' ? (
              <Button type="button" variant="ghost" loading={action === 'cancel'} disabled={busy} onClick={(event) => onRequestCancel(event.currentTarget)}>取消计划</Button>
            ) : null}
          </div>
        </div>
        {error ? <div role="alert" className="mt-3 border-l-2 border-red-500 bg-red-50 px-3 py-2 text-sm text-red-700">{error}</div> : null}
      </div>

      <DetailSection title="推荐现场">
        <div className="grid grid-cols-2 gap-x-4 gap-y-3 sm:grid-cols-3 xl:grid-cols-2">
          <Metric label="推荐动作" value={tradeCase.recommendationAction} />
          <Metric label="推荐时间" value={formatDateTime(tradeCase.recommendedAt)} />
          <Metric label="推荐价" value={formatMoney(tradeCase.recommendedPrice)} />
          <Metric label="推荐分" value={tradeCase.recommendationScore === null ? '待补充' : tradeCase.recommendationScore.toFixed(1)} />
        </div>
        <details className="mt-4 border-t border-line-soft pt-3">
          <summary className="cursor-pointer text-sm font-medium text-ink-700 outline-none focus-visible:ring-2 focus-visible:ring-brand-300">查看推荐证据 JSON</summary>
          <pre className="mt-3 max-h-72 overflow-auto whitespace-pre-wrap break-words bg-line-soft/60 p-3 text-xs leading-relaxed text-ink-600">{JSON.stringify(tradeCase.recommendationPayload, null, 2)}</pre>
        </details>
      </DetailSection>

      <DetailSection title="仓位与毛收益">
        <div className="grid grid-cols-2 gap-x-4 gap-y-3">
          <Metric label="当前持仓" value={`${tradeCase.ledger.positionQuantity} 股`} />
          <Metric label="建仓时间" value={tradeCase.ledger.openedAt ? formatDateTime(tradeCase.ledger.openedAt) : '—'} />
          <Metric label="加权成本" value={formatMoney(tradeCase.ledger.averageCost)} />
          <Metric label="最新价" value={formatMoney(tradeCase.ledger.latestPrice)} />
          <Metric label="执行偏差" value={<span className={changeClass(executionDeviation)}>{formatSignedPercent(executionDeviation)}</span>} />
          <Metric label="已实现" value={<ProfitText value={tradeCase.ledger.realizedProfit} />} />
          <Metric label="浮动收益" value={<ProfitText value={tradeCase.ledger.unrealizedProfit} />} />
          <Metric label="累计毛收益" value={<ProfitText value={tradeCase.ledger.totalProfit} />} />
        </div>
        <p className="mt-3 border-l-2 border-amber-400 bg-amber-50 px-3 py-2 text-xs leading-relaxed text-amber-800">收益未计佣金、印花税、分红和送转股</p>
      </DetailSection>

      <DetailSection title="策略表现">
        <div className="grid grid-cols-1 divide-y divide-line-soft border-y border-line-soft">
          {recommendationOutcomes.map((outcome, index) => (
            <OutcomeRow key={outcome?.horizon ?? `missing-${index}`} horizon={['T1', 'T5', 'T20'][index]} outcome={outcome} />
          ))}
        </div>
        {executionOutcomes.length ? (
          <div className="mt-4">
            <h4 className="text-xs font-semibold text-ink-600">执行后表现</h4>
            <div className="mt-2 grid grid-cols-1 divide-y divide-line-soft border-y border-line-soft">
              {executionOutcomes.map((outcome) => <OutcomeRow key={`${outcome.baselineType}-${outcome.horizon}`} horizon={outcome.horizon} outcome={outcome} />)}
            </div>
          </div>
        ) : null}
        {tradeCase.outcomeWarnings.length ? (
          <div role="alert" className="mt-3 border-l-2 border-amber-400 bg-amber-50 px-3 py-2 text-xs text-amber-800">
            {tradeCase.outcomeWarnings.map((warning) => <p key={warning}>{warning}</p>)}
          </div>
        ) : null}
      </DetailSection>

      <DetailSection
        title="成交时间线"
        action={(
          <Button
            type="button"
            variant="primary"
            icon={<Plus className="h-4 w-4" />}
            onClick={(event) => onAddFill(event.currentTarget)}
            disabled={tradeCase.status === 'CANCELLED' || busy}
          >
            新增成交
          </Button>
        )}
      >
        {tradeCase.fills.length ? (
          <ol className="relative ml-2 border-l border-line">
            {[...tradeCase.fills]
              .sort((left, right) => Date.parse(left.executedAt) - Date.parse(right.executedAt))
              .map((fill) => (
                <li key={fill.fillId} className="relative pb-4 pl-5 last:pb-0">
                  <span className={`absolute -left-1.5 top-1.5 h-3 w-3 rounded-full border-2 border-white ${fill.side === 'BUY' ? 'bg-red-500' : 'bg-emerald-500'}`} />
                  <div className="flex flex-wrap items-start justify-between gap-2">
                    <div>
                      <div className="flex items-center gap-2">
                        <Tag tone={fill.side === 'BUY' ? 'danger' : 'success'}>{fill.side}</Tag>
                        <span className="tabular text-sm font-semibold text-ink-900">{formatMoney(fill.price)} × {fill.quantity} 股</span>
                      </div>
                      <div className="mt-1 text-xs text-ink-500">{formatDateTime(fill.executedAt)}</div>
                    </div>
                    <div className="flex gap-1">
                      <IconButton label="编辑成交" disabled={busy} onClick={(event) => onEditFill(fill, event.currentTarget)} icon={<Edit3 className="h-4 w-4" />} />
                      <IconButton label="删除成交" disabled={busy} danger onClick={() => onDeleteFill(fill)} icon={<Trash2 className="h-4 w-4" />} />
                    </div>
                  </div>
                </li>
              ))}
          </ol>
        ) : (
          <p className="text-sm text-ink-500">尚未录入真实成交。</p>
        )}
      </DetailSection>
    </div>
  )
}

function CancelPlanDialog({ symbol, returnFocus, busy, onClose, onConfirm }: {
  symbol: string
  returnFocus: HTMLElement | null
  busy: boolean
  onClose: () => void
  onConfirm: () => void
}) {
  const dialogRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    const releaseScrollLock = acquireBodyScrollLock(document.body)
    dialogRef.current?.querySelector<HTMLButtonElement>('[data-cancel-plan-confirm]')?.focus()
    return () => {
      releaseScrollLock()
      returnFocus?.focus()
    }
  }, [returnFocus])

  const handleDialogKeyDown = (event: KeyboardEvent<HTMLDivElement>) => {
    if (event.key === 'Escape') {
      event.stopPropagation()
      if (!busy) {
        event.preventDefault()
        onClose()
      }
      return
    }
    if (event.key !== 'Tab') return
    const focusable = dialogRef.current?.querySelectorAll<HTMLElement>('button:not([disabled])')
    if (!focusable?.length) return
    const first = focusable[0]
    const last = focusable[focusable.length - 1]
    if (event.shiftKey && document.activeElement === first) {
      event.preventDefault()
      last.focus()
    } else if (!event.shiftKey && document.activeElement === last) {
      event.preventDefault()
      first.focus()
    }
  }

  return (
    <div className="fixed inset-0 z-[60] flex items-end justify-center bg-ink-900/40 p-0 sm:items-center sm:p-4" onMouseDown={(event) => {
      event.stopPropagation()
      if (event.target === event.currentTarget && !busy) onClose()
    }}>
      <div
        ref={dialogRef}
        role="dialog"
        aria-modal="true"
        aria-labelledby="cancel-plan-dialog-title"
        aria-describedby="cancel-plan-dialog-description"
        className="w-full rounded-t-xl border border-line bg-white shadow-float sm:max-w-md sm:rounded-xl"
        onKeyDown={handleDialogKeyDown}
      >
        <div className="border-b border-line px-4 py-3">
          <h2 id="cancel-plan-dialog-title" className="text-base font-semibold text-ink-900">取消复盘计划</h2>
        </div>
        <div className="px-4 py-5">
          <p id="cancel-plan-dialog-description" className="text-sm leading-relaxed text-ink-600">确认取消 {symbol} 的复盘计划？取消后将不能继续录入成交。</p>
          <div className="mt-5 flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
            <Button type="button" variant="ghost" disabled={busy} onClick={onClose}>返回</Button>
            <Button data-cancel-plan-confirm type="button" variant="danger" loading={busy} disabled={busy} onClick={onConfirm}>确认取消</Button>
          </div>
        </div>
      </div>
    </div>
  )
}

function ManualTradeModal({ returnFocus, busy, onClose, onSubmit, onSaved }: {
  returnFocus: HTMLElement | null
  busy: boolean
  onClose: () => void
  onSubmit: (request: ManualTradeFillRequest) => Promise<TradeCaseDetail | undefined>
  onSaved: () => void
}) {
  const dialogRef = useRef<HTMLDivElement>(null)
  const firstFieldRef = useRef<HTMLInputElement>(null)
  const [symbol, setSymbol] = useState('')
  const [companyName, setCompanyName] = useState('')
  const [side, setSide] = useState<TradeSide>('BUY')
  const [executedAt, setExecutedAt] = useState(() => formatShanghaiDateTimeLocal(new Date().toISOString()))
  const [price, setPrice] = useState('')
  const [quantity, setQuantity] = useState('100')
  const [error, setError] = useState('')

  useEffect(() => {
    const releaseScrollLock = acquireBodyScrollLock(document.body)
    firstFieldRef.current?.focus()
    return () => {
      releaseScrollLock()
      returnFocus?.focus()
    }
  }, [returnFocus])

  const handleDialogKeyDown = (event: KeyboardEvent<HTMLDivElement>) => {
    if (event.key === 'Escape') {
      event.stopPropagation()
      if (!busy) {
        event.preventDefault()
        onClose()
      }
      return
    }
    if (event.key !== 'Tab') return
    const focusable = dialogRef.current?.querySelectorAll<HTMLElement>(
      'button:not([disabled]), input:not([disabled]), [tabindex]:not([tabindex="-1"])'
    )
    if (!focusable?.length) return
    const first = focusable[0]
    const last = focusable[focusable.length - 1]
    if (event.shiftKey && document.activeElement === first) {
      event.preventDefault()
      last.focus()
    } else if (!event.shiftKey && document.activeElement === last) {
      event.preventDefault()
      first.focus()
    }
  }

  const submit = async (event: FormEvent) => {
    event.preventDefault()
    setError('')
    const normalizedSymbol = symbol.trim()
    const normalizedCompanyName = companyName.trim()
    const numericPrice = Number(price)
    const numericQuantity = Number(quantity)
    const timestamp = parseShanghaiDateTimeLocal(executedAt)
    if (!/^\d{6}$/.test(normalizedSymbol)) {
      setError('股票代码必须是 6 位数字')
      return
    }
    if (!normalizedCompanyName) {
      setError('公司名称不能为空')
      return
    }
    if (!timestamp) {
      setError('请输入有效的成交时间')
      return
    }
    if (!Number.isFinite(numericPrice) || numericPrice <= 0) {
      setError('成交价必须大于 0')
      return
    }
    if (!Number.isInteger(numericQuantity) || numericQuantity <= 0) {
      setError('成交股数必须为正整数')
      return
    }
    try {
      const response = await onSubmit({
        symbol: normalizedSymbol,
        companyName: normalizedCompanyName,
        fill: {
          side,
          executedAt: timestamp,
          price: numericPrice,
          quantity: numericQuantity
        }
      })
      if (!response) return
      onSaved()
    } catch (submitError) {
      const message = extractTradeMutationError(submitError)
      setError(message)
      toast.error(`手工成交保存失败：${message}`)
    }
  }

  return (
    <div className="fixed inset-0 z-[60] flex items-end justify-center bg-ink-900/40 p-0 sm:items-center sm:p-4" onMouseDown={(event) => {
      event.stopPropagation()
      if (event.target === event.currentTarget && !busy) onClose()
    }}>
      <div
        ref={dialogRef}
        role="dialog"
        aria-modal="true"
        aria-labelledby="manual-trade-dialog-title"
        className="max-h-[92dvh] w-full overflow-y-auto rounded-t-xl border border-line bg-white shadow-float sm:max-w-lg sm:rounded-xl"
        onKeyDown={handleDialogKeyDown}
      >
        <div className="sticky top-0 z-10 flex items-center justify-between border-b border-line bg-white px-4 py-3">
          <div>
            <h2 id="manual-trade-dialog-title" className="text-base font-semibold text-ink-900">独立录入成交</h2>
            <p className="mt-0.5 text-xs text-ink-500">不依赖推荐凭证，直接记录真实买入或卖出。</p>
          </div>
          <IconButton label="关闭独立录入" disabled={busy} onClick={onClose} icon={<X className="h-4 w-4" />} />
        </div>
        <form onSubmit={(event) => void submit(event)} className="space-y-4 px-4 py-5">
          <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
            <label className="block">
              <span className="field-label">股票代码</span>
              <input
                ref={firstFieldRef}
                className="field tabular"
                aria-label="股票代码"
                placeholder="600367"
                maxLength={6}
                inputMode="numeric"
                required
                value={symbol}
                onChange={(event) => setSymbol(event.target.value)}
              />
            </label>
            <label className="block">
              <span className="field-label">公司名称</span>
              <input
                className="field"
                aria-label="公司名称"
                placeholder="红星发展"
                required
                value={companyName}
                onChange={(event) => setCompanyName(event.target.value)}
              />
            </label>
          </div>
          <fieldset>
            <legend className="field-label">成交方向</legend>
            <div className="grid grid-cols-2 rounded-lg border border-line p-1">
              {(['BUY', 'SELL'] as TradeSide[]).map((value) => (
                <button
                  key={value}
                  type="button"
                  aria-pressed={side === value}
                  className={`rounded-md px-3 py-2 text-sm font-semibold outline-none focus-visible:ring-2 focus-visible:ring-brand-300 ${side === value ? (value === 'BUY' ? 'bg-red-50 text-red-700' : 'bg-emerald-50 text-emerald-700') : 'text-ink-500 hover:bg-line-soft'}`}
                  onClick={() => setSide(value)}
                >
                  {value === 'BUY' ? '买入 BUY' : '卖出 SELL'}
                </button>
              ))}
            </div>
          </fieldset>
          <label className="block">
            <span className="field-label">成交时间</span>
            <input
              className="field"
              aria-label="成交时间"
              type="datetime-local"
              step="1"
              required
              value={executedAt}
              onChange={(event) => setExecutedAt(event.target.value)}
            />
          </label>
          <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
            <label className="block">
              <span className="field-label">成交价</span>
              <input
                className="field tabular"
                aria-label="成交价"
                type="number"
                min="0.01"
                step="0.01"
                inputMode="decimal"
                required
                value={price}
                onChange={(event) => setPrice(event.target.value)}
              />
            </label>
            <label className="block">
              <span className="field-label">成交股数</span>
              <input
                className="field tabular"
                aria-label="成交股数"
                type="number"
                min="1"
                step="1"
                inputMode="numeric"
                required
                value={quantity}
                onChange={(event) => setQuantity(event.target.value)}
              />
            </label>
          </div>
          {error ? <div role="alert" className="border-l-2 border-red-500 bg-red-50 px-3 py-2 text-sm text-red-700">{error}</div> : null}
          <div className="flex flex-col-reverse gap-2 border-t border-line-soft pt-4 sm:flex-row sm:justify-end">
            <Button type="button" variant="ghost" disabled={busy} onClick={onClose}>取消</Button>
            <Button type="submit" variant="primary" loading={busy} disabled={busy}>保存成交</Button>
          </div>
        </form>
      </div>
    </div>
  )
}

function FillModal({ tradeCase, fill, returnFocus, busy, onClose, onSubmit, onSaved }: {
  tradeCase: TradeCaseDetail
  fill?: TradeFillView
  returnFocus: HTMLElement | null
  busy: boolean
  onClose: () => void
  onSubmit: (request: UpsertTradeFillRequest) => Promise<TradeCaseDetail | undefined>
  onSaved: () => void
}) {
  const dialogRef = useRef<HTMLDivElement>(null)
  const firstFieldRef = useRef<HTMLButtonElement>(null)
  const [side, setSide] = useState<TradeSide>(fill?.side ?? 'BUY')
  const [executedAt, setExecutedAt] = useState(() => formatShanghaiDateTimeLocal(fill?.executedAt ?? new Date().toISOString()))
  const [price, setPrice] = useState(fill ? String(fill.price) : '')
  const [quantity, setQuantity] = useState(fill ? String(fill.quantity) : '100')
  const [error, setError] = useState('')

  useEffect(() => {
    const releaseScrollLock = acquireBodyScrollLock(document.body)
    firstFieldRef.current?.focus()
    return () => {
      releaseScrollLock()
      returnFocus?.focus()
    }
  }, [returnFocus])

  const handleDialogKeyDown = (event: KeyboardEvent<HTMLDivElement>) => {
    if (event.key === 'Escape') {
      event.stopPropagation()
      if (!busy) {
        event.preventDefault()
        onClose()
      }
      return
    }
    if (event.key !== 'Tab') return
    const focusable = dialogRef.current?.querySelectorAll<HTMLElement>(
      'button:not([disabled]), input:not([disabled]), [tabindex]:not([tabindex="-1"])'
    )
    if (!focusable?.length) return
    const first = focusable[0]
    const last = focusable[focusable.length - 1]
    if (event.shiftKey && document.activeElement === first) {
      event.preventDefault()
      last.focus()
    } else if (!event.shiftKey && document.activeElement === last) {
      event.preventDefault()
      first.focus()
    }
  }

  const submit = async (event: FormEvent) => {
    event.preventDefault()
    setError('')
    const numericPrice = Number(price)
    const numericQuantity = Number(quantity)
    const timestamp = parseShanghaiDateTimeLocal(executedAt)
    if (!timestamp) {
      setError('请输入有效的成交时间')
      return
    }
    if (!Number.isFinite(numericPrice) || numericPrice <= 0) {
      setError('成交价必须大于 0')
      return
    }
    if (!Number.isInteger(numericQuantity) || numericQuantity <= 0) {
      setError('成交股数必须为正整数')
      return
    }
    const request: UpsertTradeFillRequest = {
      side,
      executedAt: timestamp,
      price: numericPrice,
      quantity: numericQuantity
    }
    try {
      const response = await onSubmit(request)
      if (!response) return
      onSaved()
      toast.success(fill ? '成交记录已更新' : '成交记录已添加')
    } catch (submitError) {
      const message = extractTradeMutationError(submitError)
      setError(message)
      toast.error(`${fill ? '更新' : '添加'}失败：${message}`)
    }
  }

  return (
    <div className="fixed inset-0 z-[60] flex items-end justify-center bg-ink-900/40 p-0 sm:items-center sm:p-4" onMouseDown={(event) => {
      event.stopPropagation()
      if (event.target === event.currentTarget && !busy) onClose()
    }}>
      <div
        ref={dialogRef}
        role="dialog"
        aria-modal="true"
        aria-labelledby="fill-dialog-title"
        className="max-h-[92dvh] w-full overflow-y-auto rounded-t-xl border border-line bg-white shadow-float sm:max-w-lg sm:rounded-xl"
        onKeyDown={handleDialogKeyDown}
      >
        <div className="sticky top-0 z-10 flex items-center justify-between border-b border-line bg-white px-4 py-3">
          <div>
            <h2 id="fill-dialog-title" className="text-base font-semibold text-ink-900">{fill ? '编辑成交' : '新增成交'}</h2>
            <p className="mt-0.5 text-xs text-ink-500">{tradeCase.symbol} {tradeCase.companyName}</p>
          </div>
          <IconButton label="关闭弹窗" disabled={busy} onClick={onClose} icon={<X className="h-4 w-4" />} />
        </div>
        <form onSubmit={(event) => void submit(event)} className="space-y-4 px-4 py-5">
          <fieldset>
            <legend className="field-label">成交方向</legend>
            <div className="grid grid-cols-2 rounded-lg border border-line p-1">
              {(['BUY', 'SELL'] as TradeSide[]).map((value) => (
                <button
                  key={value}
                  ref={value === 'BUY' ? firstFieldRef : undefined}
                  type="button"
                  aria-pressed={side === value}
                  className={`rounded-md px-3 py-2 text-sm font-semibold outline-none focus-visible:ring-2 focus-visible:ring-brand-300 ${side === value ? (value === 'BUY' ? 'bg-red-50 text-red-700' : 'bg-emerald-50 text-emerald-700') : 'text-ink-500 hover:bg-line-soft'}`}
                  onClick={() => setSide(value)}
                >
                  {value === 'BUY' ? '买入 BUY' : '卖出 SELL'}
                </button>
              ))}
            </div>
          </fieldset>
          <label className="block">
            <span className="field-label">成交时间</span>
            <input className="field" type="datetime-local" step="1" required value={executedAt} onChange={(event) => setExecutedAt(event.target.value)} />
          </label>
          <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
            <label className="block">
              <span className="field-label">每股成交价</span>
              <input className="field tabular" type="number" min="0.01" step="0.01" inputMode="decimal" required value={price} onChange={(event) => setPrice(event.target.value)} />
            </label>
            <label className="block">
              <span className="field-label">成交股数</span>
              <input className="field tabular" type="number" min="1" step="1" inputMode="numeric" required value={quantity} onChange={(event) => setQuantity(event.target.value)} />
            </label>
          </div>
          {error ? <div role="alert" className="border-l-2 border-red-500 bg-red-50 px-3 py-2 text-sm text-red-700">{error}</div> : null}
          <div className="flex flex-col-reverse gap-2 border-t border-line-soft pt-4 sm:flex-row sm:justify-end">
            <Button type="button" variant="ghost" disabled={busy} onClick={onClose}>取消</Button>
            <Button type="submit" variant="primary" loading={busy} disabled={busy}>{fill ? '保存修改' : '添加成交'}</Button>
          </div>
        </form>
      </div>
    </div>
  )
}

function DetailSection({ title, action, children }: { title: string; action?: ReactNode; children: ReactNode }) {
  return (
    <section className="border-b border-line px-4 py-4 last:border-b-0">
      <div className="mb-3 flex flex-wrap items-center justify-between gap-2">
        <h3 className="text-sm font-semibold text-ink-900">{title}</h3>
        {action}
      </div>
      {children}
    </section>
  )
}

function Metric({ label, value }: { label: string; value: ReactNode }) {
  return (
    <div className="min-w-0">
      <div className="text-xs text-ink-400">{label}</div>
      <div className="mt-1 break-words tabular text-sm font-semibold text-ink-900">{value}</div>
    </div>
  )
}

function OutcomeRow({ horizon, outcome }: { horizon: string; outcome?: TradeOutcomeView }) {
  const meta = outcome ? (outcomeMeta[outcome.status] ?? { label: outcome.status, tone: 'neutral' as const }) : null
  return (
    <div className="grid grid-cols-[48px_minmax(0,1fr)] gap-3 py-3">
      <div className="tabular text-sm font-semibold text-ink-900">{horizon}</div>
      {outcome ? (
        <div className="min-w-0">
          <div className="flex flex-wrap items-center justify-between gap-2">
            <Tag tone={meta?.tone}>{meta?.label}</Tag>
            <span className={`tabular text-sm font-semibold ${changeClass(outcome.returnPct)}`}>{formatSignedPercent(outcome.returnPct)}</span>
          </div>
          <div className="mt-2 grid grid-cols-2 gap-x-3 gap-y-1 text-xs text-ink-500">
            <span>评估价 {formatNumber(outcome.evaluationPrice)}</span>
            <span>日期 {outcome.evaluationDate ?? '—'}</span>
            <span>区间高点 <span className={changeClass(outcome.maxRunupPct)}>{formatSignedPercent(outcome.maxRunupPct)}</span></span>
            <span>区间回撤 <span className={changeClass(outcome.maxDrawdownPct)}>{formatSignedPercent(outcome.maxDrawdownPct)}</span></span>
          </div>
          <div className="mt-1 break-words text-xs text-ink-400">来源 {outcome.sourceName ?? '待补充'} · 行情时间 {formatDateTime(outcome.marketTimestamp)}</div>
        </div>
      ) : (
        <div className="text-sm text-ink-400">未评估</div>
      )}
    </div>
  )
}

function OutcomeCell({ outcome }: { outcome?: TradeOutcomeView }) {
  if (!outcome) return <span className="whitespace-nowrap text-xs text-ink-400">未评估</span>
  if (outcome.status === 'PENDING') return <span className="whitespace-nowrap text-xs text-sky-600">待成熟</span>
  if (outcome.status === 'UNAVAILABLE') return <span className="whitespace-nowrap text-xs text-red-600">数据不可用</span>
  if (outcome.status !== 'MATURED') return <span className="whitespace-nowrap text-xs text-ink-400">状态异常</span>
  return <span className={`whitespace-nowrap tabular font-medium ${changeClass(outcome.returnPct)}`}>{formatSignedPercent(outcome.returnPct)}</span>
}

function NumberCell({ value, suffix = '' }: { value: number | null; suffix?: string }) {
  return <span className="whitespace-nowrap tabular font-medium text-ink-900">{value === null ? '待补充' : `${formatNumber(value)}${suffix}`}</span>
}

function ProfitText({ value }: { value: number | null }) {
  if (value === null) return <span className="text-ink-400">待补充</span>
  return <span className={`whitespace-nowrap tabular font-semibold ${changeClass(value)}`}>{value > 0 ? '+' : ''}{formatNumber(value)} 元</span>
}

function StatusTag({ status, className = '' }: { status: TradeCaseStatus; className?: string }) {
  const meta = statusMeta[status]
  return <Tag tone={meta.tone} className={className}>{meta.label}</Tag>
}

function IconButton({ label, icon, danger = false, loading = false, ...props }: {
  label: string
  icon: ReactNode
  danger?: boolean
  loading?: boolean
} & Omit<ButtonHTMLAttributes<HTMLButtonElement>, 'children'>) {
  return (
    <button
      type="button"
      aria-label={label}
      title={label}
      {...props}
      className={`inline-flex h-9 w-9 shrink-0 items-center justify-center rounded-lg border border-transparent outline-none transition focus-visible:ring-2 focus-visible:ring-brand-300 disabled:cursor-not-allowed disabled:opacity-50 ${danger ? 'text-red-600 hover:bg-red-50' : 'text-ink-500 hover:bg-line-soft hover:text-ink-900'} ${props.className ?? ''}`}
    >
      {loading ? <RefreshCw className="h-4 w-4 animate-spin" /> : icon}
    </button>
  )
}

function canDeleteReviewCase(tradeCase: TradeCase) {
  return (tradeCase.status === 'PLANNED' || tradeCase.status === 'CANCELLED')
    && tradeCase.ledger.positionQuantity === 0
}

function positionOpenedTime(tradeCase: TradeCase) {
  const openedAt = tradeCase.ledger.openedAt ? Date.parse(tradeCase.ledger.openedAt) : Number.NaN
  if (Number.isFinite(openedAt)) return openedAt
  const createdAt = Date.parse(tradeCase.createdAt)
  return Number.isFinite(createdAt) ? createdAt : 0
}

function formatSignedMoney(value: number | null) {
  if (value === null) return '待补充'
  return `${value > 0 ? '+' : ''}${formatNumber(value)} 元`
}

function isTradeCaseDetail(tradeCase: TradeCase | undefined): tradeCase is TradeCaseDetail {
  return Boolean(tradeCase && 'fills' in tradeCase)
}

function getRecommendationOutcome(tradeCase: TradeCase, horizon: string) {
  return tradeCase.outcomes.find((outcome) => outcome.baselineType === 'RECOMMENDATION' && outcome.horizon === horizon)
}

function formatMoney(value: number | null | undefined) {
  return value === null || value === undefined ? '待补充' : `${formatNumber(value)} 元`
}
