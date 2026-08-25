#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

cd "$ROOT_DIR"

# web 镜像只 COPY apps/web-react/dist，前端必须先在 Docker 外构建，否则会部署旧产物
npm --prefix apps/web-react run build

mvn -pl apps/api -DskipTests package
docker compose up -d --build api web
