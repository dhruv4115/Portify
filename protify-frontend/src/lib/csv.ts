/**
 * day-5-dev-C.md D5-C2's CSV export. RFC 4180 quoting: a field is quoted only when it contains a
 * comma, quote or newline, and an embedded quote is doubled — the minimum that keeps a comma or
 * quote inside e.g. an instrument name or a note from corrupting the column count on the next
 * field.
 */
export function toCsv(headers: string[], rows: (string | number)[][]): string {
  return [headers, ...rows].map((row) => row.map(escapeCell).join(',')).join('\r\n');
}

function escapeCell(cell: string | number): string {
  const text = String(cell);
  return /[",\r\n]/.test(text) ? `"${text.replace(/"/g, '""')}"` : text;
}

/** Triggers a browser download of `content` as a file named `filename` — no server round trip,
 * the export is built entirely from data the page already holds. */
export function downloadCsv(filename: string, content: string): void {
  const blob = new Blob([content], { type: 'text/csv;charset=utf-8;' });
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = filename;
  document.body.appendChild(link);
  link.click();
  document.body.removeChild(link);
  URL.revokeObjectURL(url);
}
