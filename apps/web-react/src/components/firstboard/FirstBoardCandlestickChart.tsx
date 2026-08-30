import { Empty } from '../ui/Empty'
import type { FirstBoardBar } from '../../types'

interface FirstBoardCandlestickChartProps {
  bars: FirstBoardBar[]
}

const WIDTH = 920
const HEIGHT = 390
const LEFT = 54
const RIGHT = 18
const PRICE_TOP = 18
const PRICE_BOTTOM = 270
const VOLUME_TOP = 300
const VOLUME_BOTTOM = 365

export function FirstBoardCandlestickChart({ bars }: FirstBoardCandlestickChartProps) {
  if (!bars.length) return <Empty text="暂无可用日线" />

  const priceMinimum = Math.min(...bars.map((bar) => Number(bar.low)))
  const priceMaximum = Math.max(...bars.map((bar) => Number(bar.high)))
  const priceRange = Math.max(priceMaximum - priceMinimum, priceMaximum * 0.01, 0.01)
  const maxVolume = Math.max(...bars.map((bar) => Number(bar.volume)), 1)
  const plotWidth = WIDTH - LEFT - RIGHT
  const step = plotWidth / bars.length
  const candleWidth = Math.max(3, Math.min(18, step * 0.58))
  const yPrice = (value: number) => PRICE_BOTTOM - ((value - priceMinimum) / priceRange) * (PRICE_BOTTOM - PRICE_TOP)
  const yVolume = (value: number) => VOLUME_BOTTOM - (value / maxVolume) * (VOLUME_BOTTOM - VOLUME_TOP)
  const priceTicks = [priceMaximum, priceMinimum + priceRange / 2, priceMinimum]

  return (
    <div className="overflow-x-auto rounded-xl border border-line-soft bg-white p-2">
      <svg
        role="img"
        aria-label="最近二十个交易日 K 线和成交量"
        viewBox={`0 0 ${WIDTH} ${HEIGHT}`}
        className="min-w-[760px] w-full"
      >
        <rect x="0" y="0" width={WIDTH} height={HEIGHT} fill="white" />
        {priceTicks.map((tick, index) => {
          const y = PRICE_TOP + index * ((PRICE_BOTTOM - PRICE_TOP) / 2)
          return (
            <g key={tick}>
              <line x1={LEFT} x2={WIDTH - RIGHT} y1={y} y2={y} stroke="#E7E9EE" strokeDasharray="4 4" />
              <text x={LEFT - 8} y={y + 4} textAnchor="end" fontSize="11" fill="#77808F">
                {tick.toFixed(2)}
              </text>
            </g>
          )
        })}
        <line x1={LEFT} x2={WIDTH - RIGHT} y1={VOLUME_TOP - 12} y2={VOLUME_TOP - 12} stroke="#D9DDE5" />

        {bars.map((bar, index) => {
          const x = LEFT + step * index + step / 2
          const open = Number(bar.open)
          const close = Number(bar.close)
          const high = Number(bar.high)
          const low = Number(bar.low)
          const rising = close >= open
          const color = rising ? '#DC2626' : '#16A34A'
          const bodyTop = Math.min(yPrice(open), yPrice(close))
          const bodyHeight = Math.max(1.5, Math.abs(yPrice(open) - yPrice(close)))
          const volumeTop = yVolume(Number(bar.volume))
          const labelEvery = bars.length <= 10 ? 2 : 4
          return (
            <g key={`${bar.tradeDate}-${index}`}>
              <line x1={x} x2={x} y1={yPrice(high)} y2={yPrice(low)} stroke={color} strokeWidth="1.2" />
              <rect
                x={x - candleWidth / 2}
                y={bodyTop}
                width={candleWidth}
                height={bodyHeight}
                fill={rising ? "white" : color}
                stroke={color}
                strokeWidth="1.2"
              />
              <rect
                x={x - candleWidth / 2}
                y={volumeTop}
                width={candleWidth}
                height={Math.max(1, VOLUME_BOTTOM - volumeTop)}
                fill={color}
                opacity="0.55"
              />
              {index % labelEvery === 0 || index === bars.length - 1 ? (
                <text x={x} y={HEIGHT - 8} textAnchor="middle" fontSize="10" fill="#77808F">
                  {bar.tradeDate.slice(5)}
                </text>
              ) : null}
            </g>
          )
        })}
        <text x={LEFT} y={VOLUME_TOP - 18} fontSize="11" fill="#77808F">成交量</text>
      </svg>
    </div>
  )
}
