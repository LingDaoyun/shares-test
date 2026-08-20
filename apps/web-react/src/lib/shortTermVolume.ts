interface ThreeDayVolumeComparison {
  todayVolume?: number | null
  averageVolume3?: number | null
  volumeRatio3?: number | null
}

export function formatVolumeLots(value: number | null | undefined) {
  if (!isPositiveFinite(value)) return null
  if (value >= 100_000_000) return `${compact(value / 100_000_000)}亿手`
  if (value >= 10_000) return `${compact(value / 10_000)}万手`
  return `${compact(value)}手`
}

export function formatThreeDayVolumeComparison(comparison: ThreeDayVolumeComparison) {
  const today = formatVolumeLots(comparison.todayVolume)
  const average = formatVolumeLots(comparison.averageVolume3)
  const ratio = comparison.volumeRatio3
  if (!today || !average || !isPositiveFinite(ratio)) return '成交量待补'
  return `${today} / ${average} · ${ratio.toFixed(2)}×`
}

function compact(value: number) {
  return Number(value.toFixed(1)).toString()
}

function isPositiveFinite(value: number | null | undefined): value is number {
  return value !== null && value !== undefined && Number.isFinite(value) && value > 0
}
