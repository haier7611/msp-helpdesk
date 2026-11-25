# syntax=docker/dockerfile:1
# Multi-stage build: Vite build on Node, served by Caddy as a non-root user on port 8080.
FROM node:26.13.1-alpine3.24@sha256:6bd9ab43c0ec0190ca7b2d193906cb8b7edf6e7f4afa43ddeccd6452710bffb5 AS build
WORKDIR /src
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY . .
RUN npm run build

FROM caddy:2.11.9-alpine@sha256:003a97808e781aa9f7e75cc2f43d34d461099031faf5e20faefb9f61126836f0 AS runtime
RUN addgroup -S -g 10001 web \
 && adduser -S -u 10001 -G web -h /srv -s /sbin/nologin web \
 && mkdir -p /data /config \
 && chown -R web:web /data /config
COPY --chown=web:web Caddyfile /etc/caddy/Caddyfile
COPY --from=build --chown=web:web /src/dist /srv
ENV XDG_DATA_HOME=/data XDG_CONFIG_HOME=/config
USER web:web
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=5s --start-period=10s --retries=5 \
  CMD wget -q -O /dev/null http://127.0.0.1:8080/ || exit 1
CMD ["caddy", "run", "--config", "/etc/caddy/Caddyfile", "--adapter", "caddyfile"]
