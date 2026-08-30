#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
required=(AISTOCK_TEST_JDBC_URL AISTOCK_TEST_DB_USERNAME AISTOCK_TEST_DB_PASSWORD)
missing=()

for name in "${required[@]}"; do
  if [[ -z "${!name:-}" ]]; then
    missing+=("$name")
  fi
done

if (( ${#missing[@]} > 0 )); then
  echo "PostgreSQL migration verification requires: ${missing[*]}" >&2
  exit 1
fi

cd "$ROOT_DIR"
exec mvn -pl apps/api -Dtest=PostgresFlywayMigrationTest test
