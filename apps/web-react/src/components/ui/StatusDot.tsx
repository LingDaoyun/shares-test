interface StatusDotProps {
  ok: boolean
  label?: string
}

// 通过 / 未通过 状态圆点
export function StatusDot({ ok, label }: StatusDotProps) {
  return (
    <span className="inline-flex items-center gap-1.5">
      <span
        className={`inline-block h-2 w-2 rounded-full ${ok ? 'bg-success' : 'bg-danger'}`}
      />
      {label && <span className={ok ? 'text-success' : 'text-danger'}>{label}</span>}
    </span>
  )
}
