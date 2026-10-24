# The console as static files behind nginx, which also forwards /api and /ws to fleetloom-gateway.
# Build the files first: cd fleetloom-web && corepack pnpm build
FROM nginx:1.29-alpine
COPY deploy/docker/nginx.conf /etc/nginx/conf.d/default.conf
COPY fleetloom-web/dist /usr/share/nginx/html
