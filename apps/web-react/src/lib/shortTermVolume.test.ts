import { describe, expect, it } from 'vitest'
import { formatThreeDayVolumeComparison, formatVolumeLots } from './shortTermVolume'

describe('short-term volume formatting', () => {
  it('formats lots into compact concrete values', () => {
    expect(formatVolumeLots(8_600)).toBe('8600手')
    expect(formatVolumeLots(1_284_000)).toBe('128.4万手')
    expect(formatVolumeLots(120_000_000)).toBe('1.2亿手')
  })

  it('formats today, previous-three-day average, and ratio together', () => {
    expect(formatThreeDayVolumeComparison({
      todayVolume: 1_284_000,
      averageVolume3: 962_000,
      volumeRatio3: 1.3347
    })).toBe('128.4万手 / 96.2万手 · 1.33×')
  })

  it('fails closed for historical or invalid values', () => {
    expect(formatThreeDayVolumeComparison({})).toBe('成交量待补')
    expect(formatThreeDayVolumeComparison({
      todayVolume: 0,
      averageVolume3: 962_000,
      volumeRatio3: 0
    })).toBe('成交量待补')
  })
})
