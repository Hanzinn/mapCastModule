#!/usr/bin/env sh
GRADLE_HOME="$(dirname "$0")/gradle"
exec "$GRADLE_HOME/bin/gradle" "$@"
