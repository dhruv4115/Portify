import '@testing-library/jest-dom/vitest';

/**
 * jsdom has no layout engine, so every element measures 0x0 and Recharts' `ResponsiveContainer`
 * has nothing to size a chart against. Stubbing `ResizeObserver` and giving elements a fixed size
 * lets the chart mount; the assertions never depend on the resulting SVG geometry, only on which
 * state the component chose to render.
 */
class ResizeObserverStub {
  observe() {}
  unobserve() {}
  disconnect() {}
}

globalThis.ResizeObserver ??= ResizeObserverStub as unknown as typeof ResizeObserver;

for (const [property, value] of [
  ['offsetWidth', 800],
  ['offsetHeight', 400],
  ['clientWidth', 800],
  ['clientHeight', 400],
] as const) {
  Object.defineProperty(HTMLElement.prototype, property, { configurable: true, value });
}
