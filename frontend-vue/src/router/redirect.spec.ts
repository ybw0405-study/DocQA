import { describe, expect, it } from 'vitest';
import { safeInternalPath } from './redirect';

describe('safeInternalPath', () => {
  it.each([
    ['/', '/'],
    ['/documents', '/documents'],
    ['/ask?conversationId=1', '/ask?conversationId=1'],
    ['/documents#top', '/documents#top'],
  ])('keeps the in-site path %s', (raw, expected) => {
    expect(safeInternalPath(raw)).toBe(expected);
  });

  it.each([
    '//evil.example',
    '/\\evil.example',
    'http://evil.example',
    'https://evil.example',
    '/javascript:alert(1)',
    'relative/path',
    '',
  ])('falls back to the home route for %s', (raw) => {
    expect(safeInternalPath(raw)).toBe('/');
  });

  it.each([undefined, null, 42, ['//evil.example']])('falls back for the non-string %p', (raw) => {
    expect(safeInternalPath(raw)).toBe('/');
  });

  it('honours an explicit fallback', () => {
    expect(safeInternalPath('//evil.example', '/login')).toBe('/login');
  });
});
