import { renderToStaticMarkup } from 'react-dom/server'
import { describe, expect, it } from 'vitest'
import { ChipDistributionChart } from './ChipDistributionChart'

describe('chip distribution chart', () => {
  it('renders the dominant zone, current price and auditable bucket labels', () => {
    const markup = renderToStaticMarkup(
      <ChipDistributionChart
        currentPrice={10.55}
        distributionBuckets={[
          { lowPrice: 9.9, highPrice: 10.05, price: 10, chipRatioPercent: 3.2, normalizedHeight: 66.67 },
          { lowPrice: 10.05, highPrice: 10.3, price: 10.2, chipRatioPercent: 4.8, normalizedHeight: 100 },
          { lowPrice: 10.9, highPrice: 11.2, price: 11.05, chipRatioPercent: 2.1, normalizedHeight: 43.75 }
        ]}
        concentrationZones={[
          {
            rank: 1,
            lowPrice: 9.9,
            highPrice: 10.4,
            peakPrice: 10.2,
            chipRatioPercent: 46.8,
            distanceToCurrentPricePercent: -3.32,
            positionToCurrentPrice: 'BELOW'
          },
          {
            rank: 2,
            lowPrice: 10.9,
            highPrice: 11.2,
            peakPrice: 11.05,
            chipRatioPercent: 18.4,
            distanceToCurrentPricePercent: 4.74,
            positionToCurrentPrice: 'ABOVE'
          }
        ]}
        dominantPeakPrice={10.2}
        dominantZoneLow={9.9}
        dominantZoneHigh={10.4}
        dominantZoneChipRatioPercent={46.8}
        nearestOverheadZone={{
          rank: 2,
          lowPrice: 10.9,
          highPrice: 11.2,
          peakPrice: 11.05,
          chipRatioPercent: 18.4,
          distanceToCurrentPricePercent: 4.74,
          positionToCurrentPrice: 'ABOVE'
        }}
      />
    )

    expect(markup).toContain('主筹码峰')
    expect(markup).toContain('10.20')
    expect(markup).toContain('9.90 - 10.40')
    expect(markup).toContain('46.80%')
    expect(markup).toContain('最近上方筹码区')
    expect(markup).toContain('aria-label="当前价 10.55"')
    expect(markup).toContain('aria-label="价格 10.20，筹码占比 4.80%"')
  })

  it('renders a compatibility note when an archived report has no buckets', () => {
    const markup = renderToStaticMarkup(
      <ChipDistributionChart
        currentPrice={10.55}
        distributionBuckets={[]}
        concentrationZones={[]}
        dominantPeakPrice={null}
        dominantZoneLow={null}
        dominantZoneHigh={null}
        dominantZoneChipRatioPercent={null}
        nearestOverheadZone={null}
      />
    )

    expect(markup).toContain('历史版本未计算完整筹码峰')
  })

  it('does not render a percentage label on every dense bucket row', () => {
    const markup = renderToStaticMarkup(
      <ChipDistributionChart
        currentPrice={10.5}
        distributionBuckets={Array.from({ length: 12 }, (_, index) => ({
          lowPrice: 10 + index * 0.1,
          highPrice: 10.09 + index * 0.1,
          price: 10.05 + index * 0.1,
          chipRatioPercent: index === 10 ? 1.2 : 8.98,
          normalizedHeight: index === 10 ? 15 : 100
        }))}
        concentrationZones={[]}
        dominantPeakPrice={10.05}
        dominantZoneLow={10}
        dominantZoneHigh={10.09}
        dominantZoneChipRatioPercent={8.98}
        nearestOverheadZone={null}
      />
    )

    expect(markup).toContain('aria-label="价格 11.05，筹码占比 1.20%"')
    expect(markup).not.toContain('>1.20%</span>')
  })
})
