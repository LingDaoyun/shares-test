export interface RightSideSignalPresentation {
  label: string
  className: string
  emphasized: boolean
}

export function rightSideSignalPresentation(signal: string | null | undefined): RightSideSignalPresentation {
  if (signal === '右侧早期确认') {
    return {
      label: signal,
      className: 'rounded-full border-emerald-300 bg-emerald-50 px-2.5 py-1 font-semibold text-emerald-800',
      emphasized: true
    }
  }
  if (signal === '右侧早期观察') {
    return {
      label: signal,
      className: 'rounded-full border-sky-200 bg-sky-50 px-2.5 py-1 text-sky-700',
      emphasized: false
    }
  }
  return {
    label: signal?.trim() || '右侧状态待确认',
    className: 'rounded-full px-2.5 py-1',
    emphasized: false
  }
}
