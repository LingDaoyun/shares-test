import type { ChipConcentrationZone, ChipDistributionBucket } from '../../types'

interface ChipDistributionChartProps {
  currentPrice: number | null | undefined
  distributionBuckets: ChipDistributionBucket[]
  concentrationZones: ChipConcentrationZone[]
  dominantPeakPrice: number | null | undefined
  dominantZoneLow: number | null | undefined
  dominantZoneHigh: number | null | undefined
  dominantZoneChipRatioPercent: number | null | undefined
  nearestOverheadZone: ChipConcentrationZone | null | undefined
}

export function ChipDistributionChart({
  currentPrice,
  distributionBuckets,
  concentrationZones,
  dominantPeakPrice,
  dominantZoneLow,
  dominantZoneHigh,
  dominantZoneChipRatioPercent,
  nearestOverheadZone
}: ChipDistributionChartProps) {
  if (!distributionBuckets.length || dominantPeakPrice == null) {
    return (
      <p className="mt-3 border-l-2 border-line-soft pl-3 text-xs leading-relaxed text-ink-500">
        历史版本未计算完整筹码峰，仍可参考平均成本和成本分位区间。
      </p>
    )
  }

  const buckets = [...distributionBuckets].sort((left, right) => right.price - left.price)
  const maximumPrice = Math.max(...buckets.map((bucket) => bucket.highPrice))
  const minimumPrice = Math.min(...buckets.map((bucket) => bucket.lowPrice))
  const markerTop = currentPrice == null || maximumPrice <= minimumPrice
    ? null
    : clamp((maximumPrice - currentPrice) / (maximumPrice - minimumPrice) * 100, 0, 100)

  return (
    <div className="mt-4 border-t border-line-soft pt-4">
      <div className="grid gap-3 sm:grid-cols-3">
        <Summary label="主筹码峰" value={formatPrice(dominantPeakPrice)} />
        <Summary
          label="主要集中区"
          value={`${formatPrice(dominantZoneLow)} - ${formatPrice(dominantZoneHigh)}`}
          detail={formatPercent(dominantZoneChipRatioPercent)}
        />
        <Summary
          label="最近上方筹码区"
          value={nearestOverheadZone
            ? `${formatPrice(nearestOverheadZone.lowPrice)} - ${formatPrice(nearestOverheadZone.highPrice)}`
            : '暂无明显集中区'}
          detail={nearestOverheadZone ? formatPercent(nearestOverheadZone.chipRatioPercent) : undefined}
        />
      </div>

      <div className="mt-4 flex flex-wrap items-center justify-between gap-2">
        <h4 className="text-xs font-semibold text-ink-800">价格筹码分布</h4>
        <div className="flex flex-wrap gap-x-3 gap-y-1 text-[11px] text-ink-500">
          <Legend swatch="bg-emerald-400" label="主筹码区" />
          <Legend swatch="bg-amber-400" label="上方筹码" />
          <Legend swatch="bg-ink-200" label="其他价位" />
        </div>
      </div>

      <div className="relative mt-2 h-72 border-y border-line-soft py-1">
        <div
          className="grid h-full"
          style={{ gridTemplateRows: `repeat(${buckets.length}, minmax(0, 1fr))` }}
        >
          {buckets.map((bucket, index) => {
            const dominant = overlaps(bucket.lowPrice, bucket.highPrice, dominantZoneLow, dominantZoneHigh)
            const overhead = currentPrice != null && bucket.lowPrice > currentPrice
            const showPrice = shouldShowPrice(index, buckets.length, bucket, concentrationZones)
            return (
              <div
                key={`${bucket.lowPrice}-${bucket.highPrice}-${index}`}
                className="grid min-h-0 grid-cols-[4.5rem_minmax(0,1fr)_3.75rem] items-center gap-2"
                aria-label={`价格 ${formatPrice(bucket.price)}，筹码占比 ${formatPercent(bucket.chipRatioPercent)}`}
                title={`价格 ${formatPrice(bucket.price)}，筹码占比 ${formatPercent(bucket.chipRatioPercent)}`}
              >
                <span className="text-right text-[10px] tabular-nums text-ink-400">
                  {showPrice ? formatPrice(bucket.price) : ''}
                </span>
                <div className="h-1.5 overflow-hidden bg-ink-50">
                  <div
                    className={`h-full ${dominant ? 'bg-emerald-400' : overhead ? 'bg-amber-400' : 'bg-ink-200'}`}
                    style={{ width: `${clamp(bucket.normalizedHeight, 1, 100)}%` }}
                  />
                </div>
                <span className="text-[10px] tabular-nums text-ink-400">
                  {showPrice ? formatPercent(bucket.chipRatioPercent) : ''}
                </span>
              </div>
            )
          })}
        </div>

        {markerTop != null ? (
          <div
            className="pointer-events-none absolute left-[4.75rem] right-0 border-t border-rose-500"
            style={{ top: `${markerTop}%` }}
            aria-label={`当前价 ${formatPrice(currentPrice)}`}
          >
            <span className="absolute right-0 -translate-y-full bg-white px-1 text-[10px] font-medium tabular-nums text-rose-600">
              当前价 {formatPrice(currentPrice)}
            </span>
          </div>
        ) : null}
      </div>
    </div>
  )
}

function Summary({ label, value, detail }: { label: string; value: string; detail?: string }) {
  return (
    <div className="border-l-2 border-line-soft pl-3">
      <div className="text-[11px] font-medium text-ink-400">{label}</div>
      <div className="mt-1 text-sm font-semibold tabular-nums text-ink-900">{value}</div>
      {detail ? <div className="mt-0.5 text-xs tabular-nums text-ink-500">区域筹码 {detail}</div> : null}
    </div>
  )
}

function Legend({ swatch, label }: { swatch: string; label: string }) {
  return (
    <span className="inline-flex items-center gap-1.5">
      <span className={`h-1.5 w-4 ${swatch}`} aria-hidden="true" />
      {label}
    </span>
  )
}

function shouldShowPrice(
  index: number,
  total: number,
  bucket: ChipDistributionBucket,
  zones: ChipConcentrationZone[]
) {
  if (index === 0 || index === total - 1) return true
  if (zones.some((zone) => Math.abs(zone.peakPrice - bucket.price) <= Math.max(0.01, bucket.highPrice - bucket.lowPrice))) {
    return true
  }
  return index % Math.max(1, Math.ceil(total / 8)) === 0
}

function overlaps(
  low: number,
  high: number,
  targetLow: number | null | undefined,
  targetHigh: number | null | undefined
) {
  return targetLow != null && targetHigh != null && low <= targetHigh && high >= targetLow
}

function clamp(value: number, minimum: number, maximum: number) {
  return Math.max(minimum, Math.min(maximum, value))
}

function formatPrice(value: number | null | undefined) {
  return value == null || !Number.isFinite(value) ? '待补' : Number(value).toFixed(2)
}

function formatPercent(value: number | null | undefined) {
  return value == null || !Number.isFinite(value) ? '待补' : `${Number(value).toFixed(2)}%`
}
