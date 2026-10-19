#!/usr/bin/env bash
set -euo pipefail
key_file=/data/db/.replica-keyfile
if [ ! -s "$key_file" ]; then
    head -c 756 /dev/urandom | base64 > "$key_file"
fi
chown mongodb:mongodb "$key_file"
chmod 400 "$key_file"
exec /usr/local/bin/docker-entrypoint.sh mongod --replSet rs0 --keyFile "$key_file" --bind_ip_all
