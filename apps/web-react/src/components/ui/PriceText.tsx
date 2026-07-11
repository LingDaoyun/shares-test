import { changeClass, formatNumber, formatSignedPercent } from '../../lib/format'

interface PriceTextProps {
  value: number | null | undefined
}

// 价格：用 A 股惯例着色（红涨绿跌仅用于涨跌幅，绝对价格保持中性）
export function PriceText({ value }: PriceTextProps) {
  return <span className="tabular font-semibold text-ink-900">{formatNumber(value)}</span>
}

interface ChangeTextProps {
  value: number | null | undefined
}

export function ChangeText({ value }: ChangeTextProps) {
  return <span className={`tabular font-medium ${changeClass(value)}`}>{formatSignedPercent(value)}</span>
}
