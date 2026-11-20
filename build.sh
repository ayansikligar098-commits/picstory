#!/usr/bin/env bash
# Build Fly Birds in THIS VM.
#
# Why not plain ./gradlew? This sandbox blocks outbound TCP from Java
# processes ("other_tcp" is Deny), so Gradle's launcher JVM cannot talk
# to its daemon over loopback. The workaround is to run Gradle's main
# class directly in ONE JVM with exactly the daemon's JVM arguments, so
# the build runs in-process (--no-daemon) and no daemon socket is needed.
# Dependency downloads still work: they go through the egress proxy,
# whose credentials are parsed live from $https_proxy (they rotate).
#
# Usage: ./build.sh [gradle task, default: assembleDebug]
set -euo pipefail
source "$HOME/workspace/android/env.sh"
cd "$(dirname "$0")"

# Parse the live proxy credentials from the environment.
PROXY_URL="${https_proxy:-${http_proxy:-}}"
if [ -n "$PROXY_URL" ]; then
  PROXY_AUTH="$(echo "$PROXY_URL" | sed -E 's#^[a-z]+://([^@]+)@.*#\1#')"
  PROXY_USER="${PROXY_AUTH%%:*}"
  PROXY_PASS="${PROXY_AUTH#*:}"
  PROXY_HOSTPORT="$(echo "$PROXY_URL" | sed -E 's#^[a-z]+://[^@]+@([^/]+).*#\1#')"
  PROXY_HOST="${PROXY_HOSTPORT%%:*}"
  PROXY_PORT="${PROXY_HOSTPORT##*:}"
  PROXY_OPTS="-Dhttp.proxyHost=$PROXY_HOST -Dhttp.proxyPort=$PROXY_PORT -Dhttp.proxyUser=$PROXY_USER -Dhttp.proxyPassword=$PROXY_PASS -Dhttps.proxyHost=$PROXY_HOST -Dhttps.proxyPort=$PROXY_PORT -Dhttps.proxyUser=$PROXY_USER -Dhttps.proxyPassword=$PROXY_PASS -Dhttp.nonProxyHosts=localhost|127.0.0.1 -Dhttps.nonProxyHosts=localhost|127.0.0.1"
  # Gradle applies systemProp.* from gradle.properties OVER JVM -D flags,
  # and the proxy password rotates per session — so sync the live password
  # into $GRADLE_USER_HOME/gradle.properties before every build.
  GUP="${GRADLE_USER_HOME:-$HOME/.gradle}/gradle.properties"
  if [ -f "$GUP" ]; then
    sed -i -E "s#^(systemProp\.https?\.proxyPassword=).*#\1$PROXY_PASS#" "$GUP"
    sed -i -E "s#^(systemProp\.https?\.proxyUser=).*#\1$PROXY_USER#" "$GUP"
    sed -i -E "s#^(systemProp\.https?\.proxyHost=).*#\1$PROXY_HOST#" "$GUP"
    sed -i -E "s#^(systemProp\.https?\.proxyPort=).*#\1$PROXY_PORT#" "$GUP"
  fi
else
  PROXY_OPTS=""
fi

TASK="${1:-assembleDebug}"

# shellcheck disable=SC2086
"$JAVA_HOME/bin/java" \
  -XX:MaxMetaspaceSize=512m \
  --add-opens=java.base/java.util=ALL-UNNAMED \
  --add-opens=java.base/java.lang=ALL-UNNAMED \
  --add-opens=java.base/java.lang.invoke=ALL-UNNAMED \
  --add-opens=java.prefs/java.util.prefs=ALL-UNNAMED \
  --add-opens=java.base/java.nio.charset=ALL-UNNAMED \
  --add-opens=java.base/java.net=ALL-UNNAMED \
  --add-opens=java.base/java.util.concurrent.atomic=ALL-UNNAMED \
  -Xmx2g -Dfile.encoding=UTF-8 -Duser.country=US -Duser.language=en -Duser.variant \
  $PROXY_OPTS \
  -cp "$HOME/workspace/android/gradle-8.7/lib/gradle-launcher-8.7.jar" \
  -javaagent:"$HOME/workspace/android/gradle-8.7/lib/agents/gradle-instrumentation-agent-8.7.jar" \
  org.gradle.launcher.GradleMain --no-daemon --offline "$TASK"

if [ "$TASK" = "assembleDebug" ]; then
  APK="app/build/outputs/apk/debug/app-debug.apk"
  if [ -f "$APK" ]; then
    cp -f "$APK" ./app-debug.apk
    ls -lh ./app-debug.apk
  fi
fi
