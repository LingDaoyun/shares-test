import type { ButtonHTMLAttributes, ReactNode } from 'react'
import { Loader2 } from 'lucide-react'

type Variant = 'primary' | 'secondary' | 'ghost' | 'danger'

interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: Variant
  loading?: boolean
  icon?: ReactNode
}

const variantClass: Record<Variant, string> = {
  primary:
    'bg-brand-500 text-white hover:bg-brand-600 border-transparent shadow-soft disabled:bg-brand-300',
  secondary:
    'bg-white text-ink-900 hover:bg-line-soft border-line disabled:text-ink-400',
  ghost:
    'bg-transparent text-ink-600 hover:bg-line-soft border-transparent',
  danger:
    'bg-danger text-white hover:bg-red-600 border-transparent disabled:opacity-50'
}

export function Button({
  variant = 'secondary',
  loading = false,
  icon,
  className = '',
  children,
  disabled,
  ...rest
}: ButtonProps) {
  return (
    <button
      {...rest}
      disabled={disabled || loading}
      className={`inline-flex items-center justify-center gap-1.5 rounded-lg border px-3.5 py-2 text-sm font-medium transition focus:outline-none focus:ring-2 focus:ring-brand-200 disabled:cursor-not-allowed ${variantClass[variant]} ${className}`}
    >
      {loading ? <Loader2 className="h-4 w-4 animate-spin" /> : icon}
      {children}
    </button>
  )
}
