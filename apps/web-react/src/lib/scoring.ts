import type { RuleDefinition } from '../types'

export function rulePriority(rule: RuleDefinition) {
  const actionBonus = rule.action === 'REJECT' ? 18 : rule.action === 'SCORE' ? 12 : 8
  const weight = rule.conditions.reduce((sum, condition) => sum + Number(condition.weight ?? 1), 0)
  return Math.min(99, Number((rule.version * 12 + rule.conditions.length * 8 + weight * 3 + actionBonus).toFixed(1)))
}

export function actionType(action: string): 'brand' | 'danger' | 'success' | 'neutral' {
  if (action === 'REJECT') return 'danger'
  if (action === 'SCORE') return 'success'
  if (action === 'REVIEW') return 'neutral'
  return 'brand'
}

export function actionLabel(action: string) {
  const labels: Record<string, string> = {
    PASS: '通过门槛',
    REJECT: '失败则排除',
    SCORE: '评分',
    ALERT: '预警',
    DOWN_WEIGHT: '降权',
    REVIEW: '人工复核'
  }
  return labels[action] ?? action
}

export function paginate<T>(items: T[], page: number, pageSize: number) {
  const start = (page - 1) * pageSize
  return items.slice(start, start + pageSize)
}

export function rankNumber(page: number, pageSize: number, index: number) {
  return (page - 1) * pageSize + index + 1
}

export function totalPages(total: number, pageSize: number) {
  return Math.max(1, Math.ceil(total / pageSize))
}
