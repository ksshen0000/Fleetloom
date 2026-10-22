# The console as static files behind nginx, which also forwards /api and /ws to rcs-gateway.
# Build the files first: cd rcs-web && corepack pnpm build
FROM nginx:1.29-alpine
COPY deploy/docker/nginx.conf /etc/nginx/conf.d/default.conf
COPY rcs-web/dist /usr/share/nginx/html
