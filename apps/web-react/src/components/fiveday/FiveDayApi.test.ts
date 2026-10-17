import { beforeEach, describe, expect, it, vi } from 'vitest'
import { checkFiveDayExecution, createFiveDayAttestation, fetchFiveDayHistory, fetchFiveDaySnapshot, refreshFiveDayHistory, startFiveDayScan } from '../../api/fiveDay'

const { get, post } = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn() }))
vi.mock('axios', () => ({ default: { create: () => ({ get, post }) } }))

describe('Five-day strategy API isolation', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    get.mockResolvedValue({ data: {} })
    post.mockResolvedValue({ data: {} })
  })

  it('passes an explicit legacy strategy for callers that omit the optional version', async () => {
    const scope = { includeGrowth: false, includeStar: false }
    await fetchFiveDaySnapshot(scope)
    await startFiveDayScan(scope)
    await createFiveDayAttestation('600001', scope)
    expect(get).toHaveBeenCalledWith('/snapshot', { params: { ...scope, strategy: 'v1' } })
    expect(post).toHaveBeenCalledWith('/scan', undefined, { params: { ...scope, strategy: 'v1' } })
    expect(post).toHaveBeenCalledWith('/attestation/600001', undefined, { params: { ...scope, strategy: 'v1' } })
  })

  it('keeps v2 and the selected boards on read, scan, execution check, and attestation', async () => {
    const scope = { strategy: 'v2' as const, includeGrowth: true, includeStar: false }
    await fetchFiveDaySnapshot(scope)
    await startFiveDayScan(scope)
    await checkFiveDayExecution('600001', scope)
    await createFiveDayAttestation('600001', scope)
    expect(get).toHaveBeenCalledWith('/snapshot', { params: scope })
    expect(post).toHaveBeenCalledWith('/scan', undefined, { params: scope })
    expect(post).toHaveBeenCalledWith('/execution-check/600001', undefined, { params: scope })
    expect(post).toHaveBeenCalledWith('/attestation/600001', undefined, { params: scope })
  })

  it('defaults history to v1 while keeping explicit v2 history and recalculation isolated', async () => {
    await fetchFiveDayHistory()
    await refreshFiveDayHistory()
    await fetchFiveDayHistory('v2')
    await refreshFiveDayHistory('v2')
    expect(get).toHaveBeenNthCalledWith(1, '/history', { params: { strategy: 'v1' } })
    expect(post).toHaveBeenNthCalledWith(1, '/history/refresh', undefined, { params: { strategy: 'v1' } })
    expect(get).toHaveBeenNthCalledWith(2, '/history', { params: { strategy: 'v2' } })
    expect(post).toHaveBeenNthCalledWith(2, '/history/refresh', undefined, { params: { strategy: 'v2' } })
  })
})
