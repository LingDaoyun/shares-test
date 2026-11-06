import { expect, it, vi } from 'vitest'
import { fetchShortTermScanJob } from './client'

const { get } = vi.hoisted(() => ({ get: vi.fn() }))
vi.mock('axios', () => ({ default: { create: () => ({ get }) } }))

it('bounds each status request to ten seconds while preserving the same job identity', async () => {
  const status = { jobId: 'manual-current', status: 'RUNNING' }
  get.mockResolvedValue({ data: status })

  expect(await fetchShortTermScanJob('manual-current')).toBe(status)
  expect(get).toHaveBeenCalledExactlyOnceWith('/short-term/scan-jobs/manual-current', { timeout: 10000 })
})
