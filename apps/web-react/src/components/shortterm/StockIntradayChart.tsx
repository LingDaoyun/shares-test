import { useMemo, useState, type KeyboardEvent, type PointerEvent } from 'react'
import type { StockIntradayPoint } from '../../types'
import { Empty } from '../ui/Empty'

const WIDTH = 920
const HEIGHT = 390
const LEFT = 62
const RIGHT = 18
const PRICE_TOP = 18
const PRICE_BOTTOM = 260
const VOLUME_TOP = 300
const VOLUME_BOTTOM = 365
const PLOT_WIDTH = WIDTH - LEFT - RIGHT
// Keep the two session endpoints distinct without allocating time to the lunch break.
const LAST_SLOT = 241
const PRICE_COLOR = '#2563EB'
const AVERAGE_COLOR = '#EA8A23'

interface ChartPoint extends StockIntradayPoint {
  time: string
  minuteOfDay: number
  slot: number
}

function chartPoint(point: StockIntradayPoint): ChartPoint | null {
  const match = /^\d{4}-\d{2}-\d{2}T(\d{2}):(\d{2}):\d{2}$/.exec(point.minute)
  if (!match || !Number.isFinite(point.price) || point.price <= 0) return null
  const minuteOfDay = Number(match[1]) * 60 + Number(match[2])
  if (Number(match[2]) > 59) return null
  const slot = minuteOfDay >= 570 && minuteOfDay <= 690
    ? minuteOfDay - 570
    : minuteOfDay >= 780 && minuteOfDay <= 900 ? 121 + minuteOfDay - 780 : null
  return slot === null ? null : { ...point, time: `${match[1]}:${match[2]}`, minuteOfDay, slot }
}

function nonNegative(value: number | null): value is number {
  return value !== null && Number.isFinite(value) && value >= 0
}

function validAverage(value: number | null): value is number {
  return nonNegative(value) && value > 0
}

function formatValue(value: number | null, unit: string) {
  return nonNegative(value)
    ? `${value.toLocaleString('zh-CN', { maximumFractionDigits: 2 })} ${unit}`
    : '缺失'
}

function xPosition(slot: number) {
  return LEFT + slot / LAST_SLOT * PLOT_WIDTH
}

function linePath(points: ChartPoint[], field: 'price' | 'averagePrice', yPosition: (value: number) => number) {
  let previousMinute: number | null = null
  return points.map(point => {
    const value = point[field]
    if (value === null || !Number.isFinite(value) || value <= 0) {
      previousMinute = null
      return ''
    }
    const command = previousMinute !== null && point.minuteOfDay === previousMinute + 1 ? 'L' : 'M'
    previousMinute = point.minuteOfDay
    return `${command}${xPosition(point.slot)},${yPosition(value)}`
  }).join(' ')
}

export function StockIntradayChart({ points }: { points: StockIntradayPoint[] }) {
  const chartPoints = useMemo(() => points.map(chartPoint)
    .filter((point): point is ChartPoint => point !== null)
    .sort((a, b) => a.slot - b.slot), [points])
  const [selectedMinute, setSelectedMinute] = useState<string | null>(null)

  if (!chartPoints.length) return <Empty text="暂无可用分时数据" />

  const selectedMatch = chartPoints.findIndex(point => point.minute === selectedMinute)
  const selectedIndex = selectedMatch >= 0 ? selectedMatch : chartPoints.length - 1
  const selected = chartPoints[selectedIndex]
  const prices = chartPoints.flatMap(point => validAverage(point.averagePrice)
    ? [point.price, point.averagePrice] : [point.price])
  const minimum = Math.min(...prices)
  const maximum = Math.max(...prices)
  const padding = Math.max((maximum - minimum) * 0.08, maximum * 0.005, 0.01)
  const priceMinimum = minimum - padding
  const priceMaximum = maximum + padding
  const priceRange = priceMaximum - priceMinimum
  const maxVolume = Math.max(1, ...chartPoints.map(point => nonNegative(point.volume) ? point.volume : 0))
  const yPrice = (value: number) => PRICE_BOTTOM - (value - priceMinimum) / priceRange * (PRICE_BOTTOM - PRICE_TOP)
  const yVolume = (value: number) => VOLUME_BOTTOM - value / maxVolume * (VOLUME_BOTTOM - VOLUME_TOP)
  const priceTicks = [priceMaximum, (priceMinimum + priceMaximum) / 2, priceMinimum]
  const volumeWidth = PLOT_WIDTH / (LAST_SLOT + 1) * 0.68
  const selectedX = xPosition(selected.slot)
  const selectedSummary = `${selected.time}，价格 ${selected.price.toFixed(2)} 元，均价 ${validAverage(selected.averagePrice) ? `${selected.averagePrice.toFixed(2)} 元` : '缺失'}，单分钟成交量 ${formatValue(selected.volume, '手')}，成交额 ${formatValue(selected.amount, '元')}`

  function selectFromPointer(event: PointerEvent<SVGSVGElement>) {
    const bounds = event.currentTarget.getBoundingClientRect()
    if (!bounds.width) return
    const x = (event.clientX - bounds.left) / bounds.width * WIDTH
    const nearest = chartPoints.reduce((best, point) =>
      Math.abs(xPosition(point.slot) - x) < Math.abs(xPosition(best.slot) - x) ? point : best)
    setSelectedMinute(nearest.minute)
  }

  function selectFromKeyboard(event: KeyboardEvent<SVGSVGElement>) {
    let nextIndex: number
    if (event.key === 'ArrowLeft' || event.key === 'ArrowDown') nextIndex = Math.max(0, selectedIndex - 1)
    else if (event.key === 'ArrowRight' || event.key === 'ArrowUp') nextIndex = Math.min(chartPoints.length - 1, selectedIndex + 1)
    else if (event.key === 'Home') nextIndex = 0
    else if (event.key === 'End') nextIndex = chartPoints.length - 1
    else return
    event.preventDefault()
    setSelectedMinute(chartPoints[nextIndex].minute)
  }

  return <div className="space-y-2 rounded-xl border border-line-soft bg-white p-3">
    <div className="flex flex-wrap items-center gap-x-4 gap-y-1 text-xs text-ink-500">
      <span className="inline-flex items-center gap-1.5"><span className="h-0.5 w-4 bg-blue-600" />价格</span>
      <span className="inline-flex items-center gap-1.5"><span className="h-0.5 w-4 bg-orange-500" />均价</span>
      <span>移动或触摸图表选点；聚焦后可用方向键、Home / End</span>
    </div>
    <dl aria-label="选中分钟详情" className="flex flex-wrap gap-x-5 gap-y-1 rounded-lg bg-slate-50 px-3 py-2 text-xs tabular-nums">
      <div><dt className="inline text-ink-500">时间 </dt><dd className="inline">{selected.minute.slice(0, 10)} {selected.time}</dd></div>
      <div><dt className="inline text-ink-500">价格 </dt><dd className="inline">{selected.price.toFixed(2)} 元</dd></div>
      <div><dt className="inline text-ink-500">均价 </dt><dd className="inline">{validAverage(selected.averagePrice) ? `${selected.averagePrice.toFixed(2)} 元` : '缺失'}</dd></div>
      <div><dt className="inline text-ink-500">单分钟成交量 </dt><dd className="inline">{formatValue(selected.volume, '手')}</dd></div>
      <div><dt className="inline text-ink-500">成交额 </dt><dd className="inline">{formatValue(selected.amount, '元')}</dd></div>
    </dl>
    <div className="overflow-x-auto">
      <svg
        role="slider"
        tabIndex={0}
        aria-label="分时价格与成交量，使用方向键选择时间点"
        aria-valuemin={0}
        aria-valuemax={chartPoints.length - 1}
        aria-valuenow={selectedIndex}
        aria-valuetext={selectedSummary}
        viewBox={`0 0 ${WIDTH} ${HEIGHT}`}
        className="min-w-[760px] w-full touch-pan-y rounded-lg focus:outline-none focus-visible:ring-2 focus-visible:ring-blue-500"
        onPointerDown={event => {
          event.currentTarget.focus({ preventScroll: true })
          event.currentTarget.setPointerCapture?.(event.pointerId)
          selectFromPointer(event)
        }}
        onPointerMove={selectFromPointer}
        onKeyDown={selectFromKeyboard}
      >
        <rect x="0" y="0" width={WIDTH} height={HEIGHT} fill="white" />
        {priceTicks.map((tick, index) => {
          const y = PRICE_TOP + index * (PRICE_BOTTOM - PRICE_TOP) / 2
          return <g key={index}>
            <line x1={LEFT} x2={WIDTH - RIGHT} y1={y} y2={y} stroke="#E7E9EE" strokeDasharray="4 4" />
            <text x={LEFT - 8} y={y + 4} textAnchor="end" fontSize="11" fill="#77808F">{tick.toFixed(2)}</text>
          </g>
        })}
        <line x1={LEFT} x2={WIDTH - RIGHT} y1={VOLUME_TOP - 12} y2={VOLUME_TOP - 12} stroke="#D9DDE5" />
        <line x1={LEFT} x2={WIDTH - RIGHT} y1={VOLUME_BOTTOM} y2={VOLUME_BOTTOM} stroke="#E7E9EE" />
        <line x1={xPosition(120.5)} x2={xPosition(120.5)} y1={PRICE_TOP} y2={VOLUME_BOTTOM} stroke="#E7E9EE" strokeDasharray="4 4" />
        <path data-series="price" d={linePath(chartPoints, 'price', yPrice)} fill="none" stroke={PRICE_COLOR} strokeWidth="1.8" />
        <path data-series="average" d={linePath(chartPoints, 'averagePrice', yPrice)} fill="none" stroke={AVERAGE_COLOR} strokeWidth="1.5" />
        {chartPoints.map(point => {
          const x = xPosition(point.slot)
          return <g key={point.minute}>
            <circle data-series="price-point" data-minute={point.minute} cx={x} cy={yPrice(point.price)} r="1.25" fill={PRICE_COLOR} />
            {validAverage(point.averagePrice) ? <circle cx={x} cy={yPrice(point.averagePrice)} r="1" fill={AVERAGE_COLOR} /> : null}
            {nonNegative(point.volume) && point.volume > 0 ? <rect
              data-series="volume"
              data-minute={point.minute}
              x={x - volumeWidth / 2}
              y={yVolume(point.volume)}
              width={volumeWidth}
              height={VOLUME_BOTTOM - yVolume(point.volume)}
              fill={PRICE_COLOR}
              opacity="0.55"
            /> : null}
          </g>
        })}
        <line x1={selectedX} x2={selectedX} y1={PRICE_TOP} y2={VOLUME_BOTTOM} stroke="#64748B" strokeDasharray="3 3" />
        <circle cx={selectedX} cy={yPrice(selected.price)} r="3.5" fill={PRICE_COLOR} stroke="white" strokeWidth="1.5" />
        {validAverage(selected.averagePrice) ? <circle cx={selectedX} cy={yPrice(selected.averagePrice)} r="3" fill={AVERAGE_COLOR} stroke="white" strokeWidth="1.5" /> : null}
        <text x={LEFT} y={VOLUME_TOP - 18} fontSize="11" fill="#77808F">单分钟成交量（手）</text>
        <text x={LEFT - 8} y={VOLUME_TOP + 4} textAnchor="end" fontSize="10" fill="#77808F">{maxVolume.toLocaleString('zh-CN', { maximumFractionDigits: 1 })}</text>
        <text x={LEFT - 8} y={VOLUME_BOTTOM} textAnchor="end" fontSize="10" fill="#77808F">0</text>
        {[
          { slot: 0, text: '09:30' },
          { slot: 60, text: '10:30' },
          { slot: 120.5, text: '11:30 / 13:00' },
          { slot: 181, text: '14:00' },
          { slot: 241, text: '15:00' },
        ].map(tick => <text key={tick.text} x={xPosition(tick.slot)} y={HEIGHT - 8}
          textAnchor={tick.slot === LAST_SLOT ? 'end' : tick.slot === 0 ? 'start' : 'middle'} fontSize="10" fill="#77808F">{tick.text}</text>)}
      </svg>
    </div>
    <p className="text-xs text-ink-500">按报价日期展示；午休已压缩，缺失分钟保留断点，成交量与成交额均为单分钟值。</p>
  </div>
}
