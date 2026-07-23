import { renderToStaticMarkup } from 'react-dom/server'
import { describe, expect, it } from 'vitest'
import { DataTable, isRowActivationKey } from './DataTable'

describe('DataTable interactive rows', () => {
  it('recognizes keyboard row activation keys', () => {
    expect(isRowActivationKey('Enter')).toBe(true)
    expect(isRowActivationKey(' ')).toBe(true)
    expect(isRowActivationKey('Escape')).toBe(false)
  })

  it('makes clickable rows keyboard focusable', () => {
    const html = renderToStaticMarkup(
      <DataTable
        columns={[{ key: 'name', title: '名称' }]}
        data={[{ id: '1', name: '测试股票' }]}
        rowKey={(row) => row.id}
        onRowClick={() => undefined}
      />
    )

    expect(html).toContain('tabindex="0"')
    expect(html).toContain('测试股票')
  })
})
