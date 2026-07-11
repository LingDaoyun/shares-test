import { useMemo, useState } from 'react'
import { useAppStore } from '../store/appStore'
import { Card } from '../components/ui/Card'
import { SectionBanner } from '../components/ui/SectionBanner'
import { Tag } from '../components/ui/Badge'
import { Pagination } from '../components/ui/Pagination'
import { DataTable, type Column } from '../components/ui/DataTable'
import { actionLabel, actionType, paginate, rankNumber, rulePriority } from '../lib/scoring'
import type { RuleDefinition } from '../types'

const PAGE_SIZE_OPTIONS = [8, 12, 20]

export function RulesPage() {
  const rulesRaw = useAppStore((s) => s.rules)
  const loading = useAppStore((s) => s.loading)
  const [page, setPage] = useState(1)
  const [pageSize, setPageSize] = useState(8)
  const rules = useMemo(
    () => [...rulesRaw].sort((a, b) => rulePriority(b) - rulePriority(a) || b.version - a.version),
    [rulesRaw]
  )

  const paged = useMemo(() => paginate(rules, page, pageSize), [rules, page, pageSize])

  const columns: Column<RuleDefinition>[] = [
    {
      key: 'rank',
      title: '#',
      width: '48px',
      render: (_row, i) => <span className="tabular font-semibold text-ink-400">{rankNumber(page, pageSize, i)}</span>
    },
    {
      key: 'ruleCode',
      title: '规则代码',
      width: '200px',
      render: (row) => (
        <div>
          <div className="font-mono text-xs text-ink-900">{row.ruleCode}</div>
          <div className="mt-0.5 text-xs text-ink-400">{row.name}</div>
        </div>
      )
    },
    {
      key: 'priority',
      title: '优先级',
      width: '90px',
      align: 'center',
      render: (row) => <span className="tabular font-semibold text-brand-600">{rulePriority(row).toFixed(0)}</span>
    },
    {
      key: 'action',
      title: '动作',
      width: '110px',
      render: (row) => <Tag tone={actionType(row.action)}>{actionLabel(row.action)}</Tag>
    },
    {
      key: 'version',
      title: '版本',
      width: '70px',
      align: 'center',
      render: (row) => <span className="tabular text-ink-600">v{row.version}</span>
    },
    {
      key: 'conditions',
      title: '条件',
      width: '70px',
      align: 'center',
      render: (row) => <span className="tabular">{row.conditions.length}</span>
    },
    {
      key: 'description',
      title: '说明',
      render: (row) => <span className="text-sm text-ink-600">{row.description}</span>
    }
  ]

  return (
    <div className="flex flex-col gap-4">
      <SectionBanner eyebrow="RULE ENGINE" title="规则目录" description="规则按优先级排序，便于单独查看动作和版本。" />
      <Card flush>
        {loading && rules.length === 0 ? null : (
          <DataTable columns={columns} data={paged} rowKey={(row) => row.ruleCode} emptyText="暂无规则" />
        )}
        <Pagination
          page={page}
          pageSize={pageSize}
          total={rules.length}
          pageSizeOptions={PAGE_SIZE_OPTIONS}
          onPageChange={setPage}
          onPageSizeChange={(s) => {
            setPageSize(s)
            setPage(1)
          }}
        />
      </Card>
    </div>
  )
}
