#!/bin/sh
# Ensure the backups directory exists and is writable.
# The volume mount from docker-compose may create it as root,
# so this script runs as root before dropping to appuser.
mkdir -p /app/backups /app/logs
chown appuser:appgroup /app/backups /app/logs

exec gosu appuser java -jar /app/app.jar "$@"
