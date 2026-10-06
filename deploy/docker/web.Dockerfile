# syntax=docker/dockerfile:1.7
# The trading web app: built with Vite, served as static files by nginx (non-root, port 8080).
# Built from the repository root: docker build -f deploy/docker/web.Dockerfile .

# The static bundle is built once on the build machine's platform and served by nginx on each target platform.
FROM --platform=$BUILDPLATFORM node:22.23.3-alpine AS build
WORKDIR /src
# Corepack fetches the pnpm version pinned in package.json; without this it waits for a yes/no answer.
ENV COREPACK_ENABLE_DOWNLOAD_PROMPT=0
RUN corepack enable
COPY package.json pnpm-lock.yaml pnpm-workspace.yaml ./
COPY apps/web/package.json apps/web/package.json
RUN --mount=type=cache,target=/root/.local/share/pnpm/store pnpm install --frozen-lockfile --filter @prayog/web
COPY apps/web apps/web
RUN pnpm --filter @prayog/web build

FROM nginx:1.30.5-alpine
COPY deploy/docker/web/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /src/apps/web/dist /usr/share/nginx/html
# nginx's own image runs as root by default; drop to its "nginx" user and a port above 1024.
RUN chown -R nginx:nginx /var/cache/nginx /var/run /usr/share/nginx/html && \
    sed -i 's|^pid .*|pid /tmp/nginx.pid;|' /etc/nginx/nginx.conf
USER nginx
EXPOSE 8080
