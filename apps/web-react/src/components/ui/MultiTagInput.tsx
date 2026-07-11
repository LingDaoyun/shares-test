import { useState, type KeyboardEvent } from 'react'
import { X } from 'lucide-react'

interface MultiTagInputProps {
  values: string[]
  onChange: (next: string[]) => void
  suggestions?: string[]
  placeholder?: string
}

// 轻量多选标签输入：支持回车添加、可创建、可从建议补全。
export function MultiTagInput({ values, onChange, suggestions = [], placeholder = '输入后回车添加' }: MultiTagInputProps) {
  const [draft, setDraft] = useState('')

  const add = (raw: string) => {
    const v = raw.trim()
    if (!v) return
    if (values.includes(v)) return
    onChange([...values, v])
  }
  const remove = (v: string) => onChange(values.filter((x) => x !== v))

  const onKey = (e: KeyboardEvent<HTMLInputElement>) => {
    if (e.key === 'Enter' || e.key === ',') {
      e.preventDefault()
      add(draft)
      setDraft('')
    } else if (e.key === 'Backspace' && draft === '' && values.length > 0) {
      remove(values[values.length - 1])
    }
  }

  // 建议中尚未被选中的项
  const remaining = suggestions.filter((s) => !values.includes(s)).slice(0, 12)

  return (
    <div>
      <div className="flex flex-wrap items-center gap-1.5 rounded-lg border border-line bg-white px-2 py-1.5 transition focus-within:border-brand-400 focus-within:ring-2 focus-within:ring-brand-100">
        {values.map((v) => (
          <span key={v} className="tag-brand inline-flex items-center gap-1">
            {v}
            <button type="button" onClick={() => remove(v)} className="text-brand-600/70 hover:text-brand-700">
              <X className="h-3 w-3" />
            </button>
          </span>
        ))}
        <input
          className="min-w-[120px] flex-1 bg-transparent text-sm text-ink-900 outline-none placeholder:text-ink-400"
          value={draft}
          onChange={(e) => setDraft(e.target.value)}
          onKeyDown={onKey}
          placeholder={placeholder}
        />
      </div>
      {remaining.length > 0 && (
        <div className="mt-1.5 flex flex-wrap gap-1">
          {remaining.map((s) => (
            <button
              key={s}
              type="button"
              onClick={() => add(s)}
              className="tag text-ink-600 transition hover:border-brand-300 hover:bg-brand-50 hover:text-brand-600"
            >
              + {s}
            </button>
          ))}
        </div>
      )}
    </div>
  )
}
