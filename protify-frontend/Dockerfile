# Production image for the React SPA: build with Vite, serve the static output with nginx.
#
# ---- build ----
FROM node:24-alpine AS build
WORKDIR /app

# Lockfile layer cached separately from source, same reasoning as the backend image.
COPY package.json package-lock.json ./
RUN npm ci

COPY . .

# Vite inlines VITE_* at BUILD time — these are not runtime config. Changing the API URL or the
# OAuth client ID therefore requires a rebuild, not a restart. Railway passes service variables
# to the builder as build args, so declaring them here is what wires the dashboard values in.
# Both are public by design (they ship in the JS bundle either way) — neither is a secret.
ARG VITE_API_BASE_URL
ARG VITE_GOOGLE_CLIENT_ID
ENV VITE_API_BASE_URL=$VITE_API_BASE_URL
ENV VITE_GOOGLE_CLIENT_ID=$VITE_GOOGLE_CLIENT_ID

# `npm run build` is `tsc --noEmit && vite build` — a type error fails the deploy rather than
# shipping a broken bundle. npm ci above installed devDependencies, so tsc is present.
RUN npm run build

# ---- run ----
FROM nginx:alpine

COPY nginx.conf /etc/nginx/templates/default.conf.template
COPY --from=build /app/dist /usr/share/nginx/html

# Railway overrides this with the port it assigns; the default keeps `docker run -p 8081:80`
# working locally without extra flags.
ENV PORT=80
EXPOSE 80
