# ADR-0004 · Use the Google ID token directly as the bearer token

**Status:** Accepted · **Date:** Day 0 · **Owner:** Dev B · **Supersedes:** —

## Context

The customer requires real Google Sign-In. React obtains a Google **ID token** — a JWT signed
by Google, valid one hour, containing `sub`, `email`, `email_verified`, `aud` and `exp`. Two
ways to get from there to an authenticated API call:

**A · Pass the Google ID token straight through.** React sends it as
`Authorization: Bearer <google-id-token>`. Spring Security, configured as an OAuth2 resource
server with `issuer-uri: https://accounts.google.com`, fetches Google's JWKS and validates
signature, issuer, audience and expiry.

**B · Exchange it for our own JWT.** `POST /auth/session` validates the Google token once,
then issues our own short-lived access token plus a refresh token. Subsequent calls use ours.

B is what a production system would eventually do, and the reference design flags it as an
upgrade. It is worth being precise about what it actually buys.

## Decision

**Option A. The Google ID token is the bearer token. Spring Security validates it as a
resource server. We write no token-issuing code, and we parse no JWTs by hand.**

```yaml
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: https://accounts.google.com
          audiences: ${GOOGLE_CLIENT_ID}
```

Supporting rules:

- A `CurrentUserResolver` reads the `sub` claim, looks up `app_user`, and creates the row on
  first sight (JIT provisioning). It exposes the **internal** `userId` to services — the
  Google `sub` never reaches a repository.
- `email_verified: false` is rejected with 403. An unverified email claim is attacker-controlled.
- The client secret is never in the backend. This flow does not need it.
- The token lives in React memory, never `localStorage` — see Consequences.
- We never write our own JWT parsing, verification or signing.

## Consequences

**Good**

- Roughly half a day of work instead of two days. That difference is the multi-currency
  feature, which the customer actually asked for.
- **No signing key of ours to manage, rotate, leak or store.** The most security-sensitive
  code in a typical auth implementation is code we do not have.
- Google owns revocation, key rotation and expiry. Spring caches the JWKS and handles rotation.
- Nothing to get subtly wrong: no `alg: none` acceptance, no missing audience check, no
  refresh-token replay window, no token-family invalidation logic.
- Statelessness is genuine — no session store, no refresh-token table, no logout endpoint that
  has to actually work.

**Bad, and accepted**

- **One-hour expiry, and we cannot change it.** The user is silently re-prompted on a 401.
  `@react-oauth/google` handles this; the interceptor retries once. Acceptable for a demo,
  irritating for a real product.
- No refresh-token rotation, so no long-lived sessions.
- We cannot add custom claims — roles or entitlements — to the token. We resolve them from the
  database on each request, which is a query we would otherwise avoid. At our scale, invisible.
- **Google is a hard runtime dependency for authentication.** With no internet, a *new* sign-in
  fails; an already-issued token continues to validate for its lifetime against a cached JWKS.
  This is the one dependency in the system with no offline fallback, and that is correct — a
  token whose signature cannot be verified must be rejected. Mitigation is operational: sign in
  at the start of the demo (`/docs/RISKS.md` R11).
- We send Google's token to our own API, so a compromised backend sees a token usable against
  other Google-audience services that accept the same `aud`. Bounded by `aud` being *our*
  client ID, and by the token being one hour old at most.

**On `localStorage`, since the presentation will be asked about it:** the token is held in a
React state variable. `localStorage` survives a page reload, which is nicer, but it is
readable by any script on the origin, so one XSS becomes full account takeover. In-memory
storage means an XSS has to win while the tab is open, and the token dies on refresh. The
production answer is neither — it is an httpOnly `SameSite=Strict` cookie, which needs option B.

## Alternatives considered

| Alternative | Why not |
|---|---|
| **Own JWT + refresh token (option B)** | The right answer for production. Costs ~1.5 extra days for: our own signing key to protect, a refresh-token table, rotation and replay detection, a revocation story, and a logout that works. Buys: configurable expiry, custom claims, httpOnly cookies, independence from Google at request time. We do not need any of those this week |
| **Session cookies + server-side sessions** | Needs a session store, breaks the stateless resource-server model, and complicates CORS for a separate SPA origin |
| **Google OAuth authorization-code flow with the backend as the client** | More secure for a server-rendered app; unnecessary indirection for an SPA that can obtain the ID token directly. Would also put the client secret in the backend |
| **Hand-rolled JWT validation with a JWT library** | Strictly worse than the framework doing it. Every historical JWT vulnerability — `alg: none`, key confusion, missing `aud` — comes from hand-rolled validation |

## Revisit when

- The one-hour expiry becomes a real user complaint rather than a demo footnote.
- We need roles or entitlements in the token rather than from a database lookup.
- A mobile client appears, where silent re-prompting is a worse experience than on web.
- Any of these triggers means implementing option B — and this ADR already contains its design,
  which is the point of writing it down.
