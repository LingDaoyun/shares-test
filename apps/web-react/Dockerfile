FROM nginx:1.27-alpine

COPY apps/web-react/nginx.conf /etc/nginx/conf.d/default.conf
COPY apps/web-react/dist /usr/share/nginx/html

EXPOSE 80

HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=5 \
  CMD wget -qO- http://127.0.0.1/healthz | grep -q ok
