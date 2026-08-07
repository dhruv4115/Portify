# protify-frontend

React + TypeScript client for the Protify API (`day-3-dev-C.md` D3-C4). Vite, Recharts, Vitest.

```bash
npm install
cp .env.example .env.local     # optional; both values are public, neither is a secret
npm run dev                    # http://localhost:5173, proxying /api to localhost:8080
npm test                       # Vitest
npm run build                  # typecheck + production bundle
```

The dev server proxies `/api` to `http://localhost:8080` (`vite.config.ts`), so in development the
browser makes same-origin requests and CORS never comes into it. Set `VITE_API_BASE_URL` to point a
production build at a deployed API.

## The customer loop

`/` lists portfolios; `/portfolios/:id` is the whole loop on one screen — **browse → chart → add →
remove**. A write refetches all three reads together, because adding or deleting a transaction moves
the chart *and* the holdings; the backend rebuilds the projection from the remaining rows.

## Two rules this code follows strictly

**Money is a string, end to end.** The API sends decimal strings (`API_CONTRACT.md` §0.2) because
JavaScript parses a bare JSON number as an IEEE-754 double, which cannot hold decimal money exactly.
`src/lib/formatMoney.ts` rescales and groups those strings with `BigInt` — no `parseFloat`, no
`Number`. The only `Number(...)` in the app is in `PerformanceChart.toData`, converting a value into
a pixel position for the SVG; every figure a user reads still comes from the server's own string.

**The server owns validation.** No rule is re-implemented here. A failed write returns an RFC 9457
`ProblemDetail`, and `fieldErrors()` in `src/api/client.ts` maps its `errors[]` onto the inputs, so a
400 from Bean Validation and a 422 from a domain rule (`SELL` over your holding, a currency mismatch)
land in the same place on screen.

## States that are handled on purpose

Loading skeletons · empty portfolio ("Add your first transaction") · a chart with **zero** points ·
a chart with exactly **one** point, which draws no line and says so · an unpriceable holding
(`null` money, not zero) · a `stale: true` amber badge when a price or rate is old. That last one is
the visible face of the offline-safety rule: degraded data shown honestly, never a failure.
