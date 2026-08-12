import { renderToStaticMarkup } from 'react-dom/server'
import { describe, expect, it } from 'vitest'
import type { ShortTermCandidate, ShortTermMarketRegime, ShortTermValidationSummary } from '../../types'
import { ShortTermSignalEvidencePanel } from './ShortTermSignalEvidencePanel'

const candidate = {
  signalProfile: {
    primaryFamily: 'GOLDEN_CROSS_BREAKOUT',
    primaryLabel: '金叉放量突破',
    activeFamilies: ['GOLDEN_CROSS_BREAKOUT', 'SUPPORT_REVERSAL'],
    evidence: ['MA5 上穿 MA10，量比 1.52'],
    dataGaps: []
  },
  volatilityQuality: {
    atrPercent: 2.36,
    distanceToMa20Atr: 0.82,
    contractionRatio5To20: 0.76,
    breakoutExpansionRatio: 1.28,
    breakoutFromHigh20Atr: 0.41,
    state: 'CONTRACTION_BREAKOUT',
    label: '缩量整理后扩张',
    contractionBreakout: true,
    contribution: 1.6,
    dataGaps: []
  },
  score: {
    finalScore: 78.8,
    technicalRankingScore: 80,
    rankingScore: 85.4,
    stageAdjustment: 1.2,
    fundFlowAdjustment: 0.8,
    relativeStrengthContribution: 2.4,
    industryLeadershipContribution: -0.4,
    marketHeatContribution: 1,
    volatilityContribution: 1.6,
    crossSectionAdjustment: 3,
    visibleRankingAdjustment: 5.4
  }
} as unknown as ShortTermCandidate

const marketRegime: ShortTermMarketRegime = {
  state: 'RISK_ON',
  label: '风险偏好回升',
  breadthPercent: 58.4,
  medianChangePercent: 0.62,
  averageAbsoluteChangePercent: 1.85,
  advancingTurnoverSharePercent: 61.2,
  limitUpRatioPercent: 1.1,
  limitDownRatioPercent: 0.08,
  sampleCount: 5388,
  maxAction: 'LIGHT_TRIAL',
  explanation: '市场宽度和成交额结构允许轻仓试错。',
  dataGaps: []
}

const summaries: ShortTermValidationSummary[] = [{
  ruleVersion: 'short-term-right-side-v4-transparent-ranking',
  signalFamily: 'GOLDEN_CROSS_BREAKOUT',
  marketRegime: 'RISK_ON',
  horizon: 'T1',
  status: 'AVAILABLE',
  minimumSampleCount: 30,
  sampleCount: 42,
  positiveRatePercent: 57.14,
  averageNetReturnPercent: 1.12,
  medianNetReturnPercent: 0.76,
  averageMfePercent: 2.41,
  averageMaePercent: -1.08
}, {
  ruleVersion: 'short-term-right-side-v4-transparent-ranking',
  signalFamily: 'GOLDEN_CROSS_BREAKOUT',
  marketRegime: 'RISK_ON',
  horizon: 'T2',
  status: 'INSUFFICIENT_SAMPLE',
  minimumSampleCount: 30,
  sampleCount: 7,
  positiveRatePercent: null,
  averageNetReturnPercent: null,
  medianNetReturnPercent: null,
  averageMfePercent: null,
  averageMaePercent: null
}]

describe('ShortTermSignalEvidencePanel', () => {
  it('shows the signal family, regime and every active ranking contribution', () => {
    const html = renderToStaticMarkup(
      <ShortTermSignalEvidencePanel
        candidate={candidate}
        marketRegime={marketRegime}
        summaries={summaries}
        validationState="READY"
      />
    )

    expect(html).toContain('金叉放量突破')
    expect(html).toContain('风险偏好回升')
    expect(html).toContain('结构分')
    expect(html).toContain('80.00')
    expect(html).toContain('排序分')
    expect(html).toContain('85.40')
    expect(html).toContain('四因子底分 78.80 + 阶段校准 +1.20 = 结构分 80.00')
    expect(html).toContain('结构分 80.00 + 可见调整 +5.40 = 排序分 85.40')
    expect(html).toContain('资金流')
    expect(html).toContain('相对强度')
    expect(html).toContain('行业地位')
    expect(html).toContain('热门方向')
    expect(html).toContain('波动质量')
    expect(html).toContain('可见调整合计')
  })

  it('shows mature T1 statistics but never invents probabilities for an immature T2 cohort', () => {
    const html = renderToStaticMarkup(
      <ShortTermSignalEvidencePanel
        candidate={candidate}
        marketRegime={marketRegime}
        summaries={summaries}
        validationState="READY"
      />
    )

    expect(html).toContain('T1 · 42 个已成熟样本')
    expect(html).toContain('正收益占比 57.14%')
    expect(html).toContain('平均净收益 +1.12%')
    expect(html).toContain('T2 · 样本积累中 7/30')
    expect(html).not.toContain('T2 · 正收益占比')
    expect(html).toContain('历史分组仅用于校准')
  })

  it('distinguishes disabled validation from an accumulating cohort', () => {
    const disabled = summaries.map((summary) => ({
      ...summary,
      status: 'VALIDATION_DISABLED' as const,
      sampleCount: 0,
      positiveRatePercent: null,
      averageNetReturnPercent: null,
      medianNetReturnPercent: null,
      averageMfePercent: null,
      averageMaePercent: null
    }))
    const html = renderToStaticMarkup(
      <ShortTermSignalEvidencePanel
        candidate={candidate}
        marketRegime={marketRegime}
        summaries={disabled}
        validationState="READY"
      />
    )

    expect(html).toContain('T1 · 历史验证已关闭')
    expect(html).toContain('T2 · 历史验证已关闭')
    expect(html).not.toContain('样本积累中 0/30')
  })

  it('fails closed when an available status carries fewer samples than the configured minimum', () => {
    const inconsistent = summaries.map((summary) => summary.horizon === 'T1' ? {
      ...summary,
      status: 'AVAILABLE' as const,
      sampleCount: 2,
      minimumSampleCount: 30,
      positiveRatePercent: 100,
      averageNetReturnPercent: 9.99
    } : summary)
    const html = renderToStaticMarkup(
      <ShortTermSignalEvidencePanel
        candidate={candidate}
        marketRegime={marketRegime}
        summaries={inconsistent}
        validationState="READY"
      />
    )

    expect(html).toContain('T1 · 样本积累中 2/30')
    expect(html).not.toContain('正收益占比 100.00%')
    expect(html).not.toContain('平均净收益 +9.99%')
  })

  it('marks legacy score snapshots as unclosed instead of reverse engineering missing contributions', () => {
    const legacyCandidate = {
      ...candidate,
      score: {
        ...candidate.score,
        finalScore: 74,
        technicalRankingScore: undefined,
        stageAdjustment: 11,
        rankingScore: 85,
        visibleRankingAdjustment: undefined
      }
    } as ShortTermCandidate
    const html = renderToStaticMarkup(
      <ShortTermSignalEvidencePanel
        candidate={legacyCandidate}
        marketRegime={marketRegime}
        summaries={summaries}
        validationState="READY"
      />
    )

    expect(html).toContain('阶段结构分')
    expect(html).toContain('待补充')
    expect(html).toContain('排序分</p><p class="mt-1 tabular text-sm font-semibold text-ink-900">待补充')
    expect(html).toContain('历史报告缺少阶段结构分或可见调整快照，无法闭合解释')
    expect(html).toContain('不反推缺失贡献')
    expect(html).not.toContain('四因子底分 74.00 + 阶段校准 0.00 = 结构分 74.00')
    expect(html).not.toContain('结构分 74.00 + 可见调整 +11.00 = 排序分 85.00')
    expect(html).not.toContain('当前排序贡献')
  })
})
