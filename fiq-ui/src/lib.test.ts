import { describe, expect, it } from 'vitest'
import { cn, formatBytes, formatDate, label } from './lib'

describe('display utilities', () => {
  it('formats operational values without leaking invalid numbers', () => {
    expect(formatBytes(0)).toBe('0 B')
    expect(formatBytes(128 * 1024 * 1024)).toBe('128 MiB')
    expect(formatBytes(Number.NaN)).toBe('0 B')
  })

  it('formats absent timestamps and machine labels', () => {
    expect(formatDate(null)).toBe('—')
    expect(label('AWAITING_APPROVAL')).toBe('Awaiting Approval')
  })

  it('joins only active class values', () => {
    expect(cn('a', false, undefined, 'b')).toBe('a b')
  })
})
