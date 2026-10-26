import { useState, type KeyboardEvent, type PointerEvent } from 'react'
import { Empty } from '../ui/Empty'
import type { StockDailyBar } from '../../types'

interface LeaderCandlestickChartProps {
  bars: StockDailyBar[]
  interactive?: boolean
}

const WIDTH = 920
const HEIGHT = 390
const LEFT = 54
const RIGHT = 18
const PRICE_TOP = 18
const PRICE_BOTTOM = 270
const VOLUME_TOP = 300
const VOLUME_BOTTOM = 365

export function LeaderCandlestickChart({ bars, interactive = false }: LeaderCandlestickChartProps) {
  const [selectedIndex, setSelectedIndex] = useState<number | null>(null)
  if (!bars.length) return <Empty text="暂无可用日线" />

  const low = Math.min(...bars.map((bar) => Number(bar.low)))
  const high = Math.max(...bars.map((bar) => Number(bar.high)))
  const padding = Math.max((high - low) * 0.04, high * 0.005, 0.01)
  const priceMinimum = low - padding
  const priceMaximum = high + padding
  const priceRange = priceMaximum - priceMinimum
  const maxVolume = Math.max(...bars.map((bar) => Number(bar.volume)), 1)
  const plotWidth = WIDTH - LEFT - RIGHT
  const step = plotWidth / bars.length
  const candleWidth = Math.max(1, Math.min(18, step * 0.58))
  const yPrice = (value: number) => PRICE_BOTTOM - ((value - priceMinimum) / priceRange) * (PRICE_BOTTOM - PRICE_TOP)
  const yVolume = (value: number) => VOLUME_BOTTOM - (value / maxVolume) * (VOLUME_BOTTOM - VOLUME_TOP)
  const priceTicks = [priceMaximum, priceMinimum + priceRange / 2, priceMinimum]
  const activeIndex = Math.min(selectedIndex ?? bars.length - 1, bars.length - 1)
  const selected = bars[activeIndex]
  const activeX = LEFT + step * activeIndex + step / 2

  function selectPoint(event: PointerEvent<SVGSVGElement>) {
    const bounds = event.currentTarget.getBoundingClientRect()
    if (!bounds.width) return
    const x = (event.clientX - bounds.left) / bounds.width * WIDTH
    setSelectedIndex(Math.max(0, Math.min(bars.length - 1, Math.floor((x - LEFT) / step))))
  }

  function selectWithKeyboard(event: KeyboardEvent<SVGSVGElement>) {
    let next = activeIndex
    if (event.key === 'ArrowLeft') next -= 1
    else if (event.key === 'ArrowRight') next += 1
    else if (event.key === 'Home') next = 0
    else if (event.key === 'End') next = bars.length - 1
    else return
    event.preventDefault()
    setSelectedIndex(Math.max(0, Math.min(bars.length - 1, next)))
  }

  return (
    <div className="space-y-2">
      {interactive ? <>
        <dl aria-label="日线数值" className="flex flex-wrap gap-x-5 gap-y-2 rounded-lg bg-line-soft/50 px-3 py-2 text-xs tabular">
          <div><dt className="text-ink-400">交易日</dt><dd className="mt-1 font-medium text-ink-900">{selected.tradeDate}</dd></div>
          {([['开', selected.open], ['高', selected.high], ['低', selected.low], ['收', selected.close]] as const).map(([label, value]) =>
            <div key={label}><dt className="text-ink-400">{label}（元）</dt><dd className="mt-1 font-medium text-ink-900">{Number(value).toFixed(2)}</dd></div>)}
          <div><dt className="text-ink-400">成交量（手）</dt><dd className="mt-1 font-medium text-ink-900">{selected.volume === null ? '缺失' : Number(selected.volume).toLocaleString('zh-CN', { maximumFractionDigits: 2 })}</dd></div>
        </dl>
        <p className="text-xs text-ink-400">移动鼠标或点按查看每日数值；聚焦图表后可用左右方向键切换。</p>
      </> : null}
      <div className="overflow-x-auto rounded-xl border border-line-soft bg-white p-2">
        <svg
          role="img"
          aria-label={`最近 ${bars.length} 个交易日 K 线和成交量${interactive ? '，可用左右方向键查看' : ''}`}
          tabIndex={interactive ? 0 : undefined}
          onPointerMove={interactive ? selectPoint : undefined}
          onPointerDown={interactive ? selectPoint : undefined}
          onKeyDown={interactive ? selectWithKeyboard : undefined}
          viewBox={`0 0 ${WIDTH} ${HEIGHT}`}
          className="min-w-[760px] w-full rounded outline-none focus-visible:ring-2 focus-visible:ring-brand-300"
        >
          <rect x="0" y="0" width={WIDTH} height={HEIGHT} fill="white" />
          {priceTicks.map((tick, index) => {
            const y = PRICE_TOP + index * ((PRICE_BOTTOM - PRICE_TOP) / 2)
            return (
              <g key={index}>
                <line x1={LEFT} x2={WIDTH - RIGHT} y1={y} y2={y} stroke="#E7E9EE" strokeDasharray="4 4" />
                <text x={LEFT - 8} y={y + 4} textAnchor="end" fontSize="11" fill="#77808F">
                  {tick.toFixed(2)}
                </text>
              </g>
            )
          })}
          <line x1={LEFT} x2={WIDTH - RIGHT} y1={VOLUME_TOP - 12} y2={VOLUME_TOP - 12} stroke="#D9DDE5" />
          {interactive ? <line x1={activeX} x2={activeX} y1={PRICE_TOP} y2={VOLUME_BOTTOM} stroke="#94A3B8" strokeDasharray="3 3" /> : null}
          {bars.map((bar, index) => {
            const x = LEFT + step * index + step / 2
            const open = Number(bar.open)
            const close = Number(bar.close)
            const high = Number(bar.high)
            const low = Number(bar.low)
            const volume = Number(bar.volume)
            const rising = close >= open
            const color = rising ? '#DC2626' : '#16A34A'
            const bodyTop = Math.min(yPrice(open), yPrice(close))
            const bodyHeight = Math.max(1.5, Math.abs(yPrice(open) - yPrice(close)))
            const volumeTop = yVolume(volume)
            const labelEvery = Math.max(1, Math.ceil(bars.length / 8))
            return (
              <g key={`${bar.tradeDate}-${index}`}>
                <line x1={x} x2={x} y1={yPrice(high)} y2={yPrice(low)} stroke={color} strokeWidth="1.2" />
                <rect x={x - candleWidth / 2} y={bodyTop} width={candleWidth} height={bodyHeight}
                  fill={rising ? 'white' : color} stroke={color} strokeWidth="1.2" />
                {volume > 0 ? <rect x={x - candleWidth / 2} y={volumeTop} width={candleWidth}
                  height={VOLUME_BOTTOM - volumeTop} fill={color} opacity="0.55" /> : null}
                {(index % labelEvery === 0 && index < bars.length - Math.max(1, labelEvery / 2)) || index === bars.length - 1 ? (
                  <text x={x} y={HEIGHT - 8} textAnchor="middle" fontSize="10" fill="#77808F">
                    {bar.tradeDate.slice(5)}
                  </text>
                ) : null}
              </g>
            )
          })}
          <text x={LEFT} y={VOLUME_TOP - 18} fontSize="11" fill="#77808F">成交量{interactive ? '（手）' : ''}</text>
        </svg>
      </div>
    </div>
  )
}
