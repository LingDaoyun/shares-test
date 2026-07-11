#!/bin/sh
set -eu

mkdir -p /data
chown app:app /data

exec setpriv --reuid=app --regid=app --init-groups java -jar /app/app.jar
