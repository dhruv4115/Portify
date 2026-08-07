import { describe, expect, it, vi } from 'vitest';
import { downloadCsv, toCsv } from '../lib/csv';

describe('toCsv', () => {
  it('joins headers and rows with commas and CRLF', () => {
    expect(toCsv(['A', 'B'], [['1', '2'], ['3', '4']])).toBe('A,B\r\n1,2\r\n3,4');
  });

  it('quotes a field containing a comma', () => {
    expect(toCsv(['Name'], [['Reliance, Industries']])).toBe('Name\r\n"Reliance, Industries"');
  });

  it('quotes a field containing a double quote, doubling the embedded quote', () => {
    expect(toCsv(['Note'], [['say "hi"']])).toBe('Note\r\n"say ""hi"""');
  });

  it('quotes a field containing a newline', () => {
    expect(toCsv(['Note'], [['line one\nline two']])).toBe('Note\r\n"line one\nline two"');
  });

  it('leaves a plain numeric or text field unquoted', () => {
    expect(toCsv(['Qty'], [[10]])).toBe('Qty\r\n10');
  });
});

describe('downloadCsv', () => {
  it('creates an object URL, clicks a download link, then revokes it', () => {
    const createObjectURL = vi.fn(() => 'blob:mock-url');
    const revokeObjectURL = vi.fn();
    vi.stubGlobal('URL', { ...URL, createObjectURL, revokeObjectURL });

    const clickSpy = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {});

    downloadCsv('holdings.csv', 'A,B\r\n1,2');

    expect(createObjectURL).toHaveBeenCalledTimes(1);
    expect(clickSpy).toHaveBeenCalledTimes(1);
    expect(revokeObjectURL).toHaveBeenCalledWith('blob:mock-url');

    clickSpy.mockRestore();
    vi.unstubAllGlobals();
  });
});
