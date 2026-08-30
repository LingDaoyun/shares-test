#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="$ROOT_DIR/.env.local"

if [[ -f "$ENV_FILE" ]]; then
  set -a
  # shellcheck disable=SC1090
  source "$ENV_FILE"
  set +a
fi

if [[ -z "${JDBC_DATABASE_PASSWORD:-}" ]]; then
  echo "JDBC_DATABASE_PASSWORD is required; set it in .env.local or the current environment." >&2
  exit 1
fi

export JDBC_DATABASE_PASSWORD

cd "$ROOT_DIR"

# web 镜像只 COPY apps/web-react/dist，前端必须先在 Docker 外构建，否则会部署旧产物
npm --prefix apps/web-react run build

mvn -pl apps/api -DskipTests package
docker compose up -d --build api web
