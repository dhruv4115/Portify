# Deploying Protify to Railway

Takes you from a clean repo to a public URL anyone can open. Three Railway services in one
project — **MySQL**, **api**, **web** — plus one change in the Google Cloud Console.

Everything the app needs is already env-driven (`SERVER_PORT`, `DB_URL`, `CORS_ALLOWED_ORIGINS`,
`VITE_API_BASE_URL`), so nothing in `src/` changes to deploy. The files that make this work are
[`railway.json`](../railway.json), [`docker/Dockerfile`](../docker/Dockerfile),
[`protify-frontend/Dockerfile`](../protify-frontend/Dockerfile) and
[`protify-frontend/nginx.conf`](../protify-frontend/nginx.conf).

> **Cost.** Railway has no permanent free tier. New accounts get trial credit; after that the
> three services together run roughly **$5–10/month** at idle. MySQL's volume is the part that
> bills even when nothing is running.

---

## 0. Prerequisites

- The branch you want to deploy is **pushed to GitHub** — Railway deploys from the repo, not
  from your working tree.
- A Railway account (<https://railway.app>, sign in with GitHub).
- Your existing Google OAuth **Client ID** (the one already in your `.env`).

---

## 1. Create the project and the database

1. Railway dashboard → **New Project** → **Deploy from GitHub repo** → pick this repo.
   Railway will start building something immediately — let it fail, step 2 fixes it.
2. In the project canvas: **+ New** → **Database** → **Add MySQL**.
3. Click the MySQL service → **Settings** → rename it to exactly **`MySQL`**. The variable
   references in step 3 are by service name, so this has to match.

Flyway runs on every boot, so the schema and `V5__demo_seed.sql` apply themselves the first
time the API connects. There is no manual migration step.

---

## 2. Configure the API service

Click the service Railway created from your repo → **Settings**:

| Setting | Value |
|---|---|
| **Service name** | `api` |
| **Root Directory** | `/` (leave empty) |
| **Build → Builder** | Dockerfile |
| **Build → Dockerfile Path** | `docker/Dockerfile` |

`railway.json` at the repo root already sets the healthcheck to `/actuator/health` with a
300-second timeout — Spring Boot plus Flyway on a cold container takes well over the 30-second
default, and a shorter timeout makes Railway kill a perfectly healthy boot.

---

## 3. API variables

**Variables** tab → **Raw Editor** → paste, then replace the two placeholder values:

```env
PORT=8080
SERVER_PORT=8080

DB_URL=jdbc:mysql://${{MySQL.MYSQLHOST}}:${{MySQL.MYSQLPORT}}/${{MySQL.MYSQLDATABASE}}?serverTimezone=UTC&useSSL=false&allowPublicKeyRetrieval=true
DB_USERNAME=${{MySQL.MYSQLUSER}}
DB_PASSWORD=${{MySQL.MYSQLPASSWORD}}

JAVA_TOOL_OPTIONS=-Duser.timezone=UTC -Djava.net.preferIPv6Addresses=true

GOOGLE_CLIENT_ID=replace-with-your-client-id.apps.googleusercontent.com
CORS_ALLOWED_ORIGINS=http://localhost:5173

MARKET_DATA_PROVIDER=yahoo
FX_PROVIDER=frankfurter
FEATURES_INSIGHTS_ENABLED=false
ADMIN_EMAILS=
```

Three of these are load-bearing in non-obvious ways:

- **`JAVA_TOOL_OPTIONS` must keep `-Duser.timezone=UTC`.** The Dockerfile sets that variable
  itself; a Railway variable of the same name *replaces* it rather than appending. Drop it and
  you reintroduce exactly the `DATETIME` drift that `docs/RISKS.md` R13 exists to prevent.
- **`-Djava.net.preferIPv6Addresses=true`** is there because Railway's private network is
  IPv6-only. `mysql.railway.internal` has no A record, and a JVM preferring IPv4 fails to
  connect with a misleading "Communications link failure".
- **`allowPublicKeyRetrieval=true`** is required by MySQL 8.4's `caching_sha2_password` over an
  unencrypted link. The link is unencrypted because it never leaves Railway's private network.

`CORS_ALLOWED_ORIGINS` is a placeholder for now — you don't know the web URL yet. Step 6 fixes it.

Now **Deploy**. Watch for `Started PortfolioApplication` in the logs.

---

## 4. Give the API a public URL

API service → **Settings** → **Networking** → **Generate Domain** → port **8080**.

You get something like `api-production-a1b2.up.railway.app`. Verify it before going further:

```bash
curl https://<your-api-domain>/actuator/health
```

Expect `{"status":"UP",...}`. A `503` with `"db":{"status":"DOWN"}` means the `DB_URL` reference
didn't resolve — check the MySQL service is named exactly `MySQL`.

---

## 5. Deploy the frontend

Project canvas → **+ New** → **GitHub Repo** → same repo again. Then **Settings**:

| Setting | Value |
|---|---|
| **Service name** | `web` |
| **Root Directory** | `protify-frontend` |
| **Build → Dockerfile Path** | `Dockerfile` |

**Variables** tab:

```env
VITE_API_BASE_URL=https://<your-api-domain>
VITE_GOOGLE_CLIENT_ID=<the same client ID as the API>
```

No trailing slash on `VITE_API_BASE_URL` — [`client.ts`](../protify-frontend/src/api/client.ts)
appends `/api/v1`, so a trailing slash produces `//api/v1` and 404s.

Vite inlines `VITE_*` at **build** time, not runtime. Changing either value later needs a
**redeploy**, not a restart — a restart serves the old bundle with the old URL baked in.

Then **Networking** → **Generate Domain** → port **80**.

---

## 6. Close the CORS and OAuth loop

Two settings still point at localhost. Both must name the `web` domain from step 5.

**a. Backend CORS** — `api` service → Variables:

```env
CORS_ALLOWED_ORIGINS=https://<your-web-domain>
```

Redeploy `api`. Until you do, every browser call from the deployed frontend fails preflight.

**b. Google OAuth** — <https://console.cloud.google.com/apis/credentials> → your OAuth 2.0
Client ID → **Authorised JavaScript origins** → **Add URI**:

```
https://<your-web-domain>
```

Save. Google takes a few minutes to propagate. Origins must be scheme + host with **no path and
no trailing slash**.

If the OAuth consent screen is still in **Testing**, only accounts listed under **Test users**
can sign in — add anyone you want to demo to, or **Publish** the app.

---

## 7. Verify

| Check | Expected |
|---|---|
| `https://<web-domain>` | Sign-in screen renders |
| Sign in with Google | Lands on the portfolio list, no console CORS error |
| Hard-refresh on `/portfolios/1` | Still loads (nginx SPA fallback, not a 404) |
| `https://<api-domain>/actuator/health` | `{"status":"UP"}` |
| `https://<api-domain>/swagger-ui.html` | Swagger UI loads |

First sign-in JIT-provisions your user row — there's no registration step.

---

## Troubleshooting

**Healthcheck fails, logs show `Communications link failure`.**
The IPv6 flag is missing or `JAVA_TOOL_OPTIONS` got overwritten. Re-paste the full value from
step 3 — both flags, one line.

**`Access-Control-Allow-Origin` error in the browser console.**
`CORS_ALLOWED_ORIGINS` doesn't exactly match the browser's origin. It's an exact string match:
`https://` not `http://`, no trailing slash, no port.

**Sign-in popup closes immediately with `origin_mismatch`.**
Step 6b isn't done, or hasn't propagated yet. Confirm the origin in the Google console matches
the address bar character for character.

**Frontend loads but every API call 404s.**
`VITE_API_BASE_URL` has a trailing slash, or was changed without a redeploy.

**Deploy is slow.**
The first build compiles the whole Maven project and runs the Surefire suite. Later builds reuse
the cached dependency layer. [`.dockerignore`](../.dockerignore) keeps ~170MB of `target/` and
`node_modules/` out of the upload — don't remove it, and don't add `.gitignore` or `.env.example`
to it (`docker/Dockerfile` copies both, and `ConfigurationSmokeTest` reads them from disk).

---

## What is *not* deployed

The **insights** service (`services/insights`) is left out — `FEATURES_INSIGHTS_ENABLED=false`
makes the endpoint answer `501` and the Java client never calls out, which is the documented
default. To enable it, add a fourth service with root directory `services/insights`, then set
`FEATURES_INSIGHTS_ENABLED=true` and
`INSIGHTS_SERVICE_URL=http://${{insights.RAILWAY_PRIVATE_DOMAIN}}:8000` on `api`.
