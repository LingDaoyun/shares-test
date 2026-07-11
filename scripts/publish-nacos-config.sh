#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SERVER_ADDR="${NACOS_SERVER_ADDR:-127.0.0.1:8848}"
GROUP="${NACOS_GROUP:-AI_STOCK}"
DATA_ID="${NACOS_CONFIG_DATA_ID:-ai-stock-api.yml}"
CONFIG_FILE="${NACOS_CONFIG_FILE:-$ROOT_DIR/infra/nacos/ai-stock-api.yml}"

if [[ "$SERVER_ADDR" != http://* && "$SERVER_ADDR" != https://* ]]; then
  SERVER_ADDR="http://$SERVER_ADDR"
fi

if [[ ! -f "$CONFIG_FILE" ]]; then
  echo "Nacos config file not found: $CONFIG_FILE" >&2
  exit 1
fi

curl -fsS --max-time 10 -X POST "$SERVER_ADDR/nacos/v1/cs/configs" \
  --data-urlencode "dataId=$DATA_ID" \
  --data-urlencode "group=$GROUP" \
  --data-urlencode "type=yaml" \
  --data-urlencode "content@$CONFIG_FILE"

echo
echo "Published $DATA_ID to $GROUP at $SERVER_ADDR"
