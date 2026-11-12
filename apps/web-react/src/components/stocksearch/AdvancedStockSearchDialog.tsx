import { useState } from 'react'
import { extractErrorMessage } from '../../lib/format'
import { Button } from '../ui/Button'
import { DetailOverlay } from '../ui/DetailOverlay'
import { toast } from '../ui/Toast'
import { parseStockSearchFilters, StockSearchFilterFields, type StockSearchFilterDraft } from './StockSearchFilterFields'

export function AdvancedStockSearchDialog({ initialValue, onApply, onClose }: {
  initialValue: StockSearchFilterDraft
  onApply: (value: StockSearchFilterDraft) => void
  onClose: () => void
}) {
  const [draft, setDraft] = useState<StockSearchFilterDraft>(() => ({ ...initialValue }))

  function apply() {
    try {
      parseStockSearchFilters(draft)
    } catch (error) {
      toast.warning(extractErrorMessage(error))
      return
    }
    onApply(draft)
  }

  return <DetailOverlay open title="高级搜索" subtitle="设置板块与数值范围，应用后点击“搜索股票”查询。" onClose={onClose}>
    <form className="space-y-5" onSubmit={event => { event.preventDefault(); apply() }}>
      <StockSearchFilterFields value={draft} onChange={setDraft} />
      <div className="flex justify-end gap-2 border-t border-line-soft pt-4">
        <Button type="button" onClick={onClose}>取消</Button>
        <Button type="submit" variant="primary">应用条件</Button>
      </div>
    </form>
  </DetailOverlay>
}
