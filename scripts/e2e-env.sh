#!/usr/bin/env bash
# Starts, bootstraps and stops the environment the E2E suite runs against.
#
#   scripts/e2e-env.sh up        # Postgres + Mailpit, backend, web app, super admin
#   scripts/e2e-env.sh status
#   scripts/e2e-env.sh down      # stop everything, keep the database
#   scripts/e2e-env.sh down -v   # stop everything and delete the database
#
# Environment (all optional):
#   E2E_APP_DIR             app checkout (default: ../beauty_client_management)
#   E2E_API_PORT            backend port (default 8080)
#   E2E_WEB_PORT            web port (default 5174 — the backend's dev CORS/SITE_URL origin)
#   E2E_SUPERADMIN_EMAIL    default e2e-superadmin@example.test
#   E2E_SUPERADMIN_PASSWORD generated on first run and kept in .e2e/e2e.env
#   E2E_SKIP_BUILD=1        reuse the existing backend install and web build
#
# State lives in .e2e/ (git-ignored): generated credentials, PIDs and logs.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
STATE="$ROOT/.e2e"
LOGS="$STATE/logs"
ENV_FILE="$STATE/e2e.env"
APP_DIR="$(cd "${E2E_APP_DIR:-$ROOT/../beauty_client_management}" 2>/dev/null && pwd || true)"

API_PORT="${E2E_API_PORT:-8080}"
WEB_PORT="${E2E_WEB_PORT:-5174}"
DB_PORT="${E2E_DB_PORT:-55432}"
SMTP_PORT="${E2E_SMTP_PORT:-1025}"
MAILPIT_PORT="${E2E_MAILPIT_PORT:-8025}"
API_URL="http://127.0.0.1:$API_PORT"
WEB_URL="http://127.0.0.1:$WEB_PORT"
MAILPIT_URL="http://127.0.0.1:$MAILPIT_PORT"

log() { printf '[e2e-env] %s\n' "$*" >&2; }
die() { log "ERROR: $*"; exit 1; }

compose() {
  if docker compose version >/dev/null 2>&1; then
    docker compose -f "$ROOT/docker-compose.e2e.yml" "$@"
  elif command -v docker-compose >/dev/null 2>&1; then
    docker-compose -f "$ROOT/docker-compose.e2e.yml" "$@"
  else
    die "neither 'docker compose' nor 'docker-compose' is available"
  fi
}

# The backend's Gradle 8.7 build needs JDK 17 (it cannot run on 23+).
ensure_java17() {
  if [[ -z "${JAVA_HOME:-}" ]] && [[ -x /usr/libexec/java_home ]]; then
    JAVA_HOME="$(/usr/libexec/java_home -v 17 2>/dev/null || true)"
  fi
  [[ -n "${JAVA_HOME:-}" ]] || die "set JAVA_HOME to a JDK 17"
  "$JAVA_HOME/bin/java" -version 2>&1 | grep -q '"17\.' || die "JAVA_HOME ($JAVA_HOME) is not JDK 17"
  export JAVA_HOME
}

port_in_use() { lsof -nP -iTCP:"$1" -sTCP:LISTEN >/dev/null 2>&1 || (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null; }

# wait_for <url> <expected-status> <seconds> <what> [method]
wait_for() {
  local url=$1 expected=$2 seconds=$3 what=$4 method=${5:-GET} status
  for ((i = 0; i < seconds; i++)); do
    status=$(curl -s -o /dev/null -w '%{http_code}' -X "$method" --max-time 2 "$url" || true)
    [[ "$status" == "$expected" ]] && { log "$what ready ($url)"; return 0; }
    sleep 1
  done
  die "$what not ready after ${seconds}s ($url answered '${status:-nothing}'); see $LOGS"
}

stop_pid() {
  local name=$1 file="$STATE/$1.pid"
  [[ -f "$file" ]] || return 0
  local pid; pid=$(cat "$file")
  if kill -0 "$pid" 2>/dev/null; then
    log "stopping $name (pid $pid)"
    # Children too: the start scripts exec java/node under a shell.
    pkill -TERM -P "$pid" 2>/dev/null || true
    kill -TERM "$pid" 2>/dev/null || true
    for _ in {1..20}; do kill -0 "$pid" 2>/dev/null || break; sleep 0.5; done
    kill -KILL "$pid" 2>/dev/null || true
  fi
  rm -f "$file"
}

start_backend() {
  local bin="$APP_DIR/backend/build/install/beauty-backend/bin/beauty-backend"
  [[ -x "$bin" ]] || die "backend not built ($bin); run without E2E_SKIP_BUILD"
  log "starting backend on :$API_PORT"
  # development + explicit Postgres with the H2 fallback OFF: if the database is
  # unreachable the backend must fail to start, not silently run on in-memory H2.
  env \
    PORT="$API_PORT" \
    APP_ENV=development \
    DB_URL="jdbc:postgresql://127.0.0.1:$DB_PORT/beautydb" \
    DB_USER=beauty \
    DB_PASSWORD="${E2E_DB_PASSWORD:-e2e-local-only}" \
    DB_ALLOW_H2_FALLBACK=false \
    SITE_URL="$WEB_URL" \
    SUPER_ADMIN_EMAILS="$ADMIN_EMAIL" \
    MAIL_HOST=127.0.0.1 \
    MAIL_PORT="$SMTP_PORT" \
    MAIL_STARTTLS=false \
    MAIL_SSL_ON_CONNECT=false \
    MAIL_FROM=e2e@example.test \
    UPLOAD_DIR="$STATE/uploads" \
    STORAGE_PROVIDER=local \
    nohup "$bin" >>"$LOGS/backend.log" 2>&1 &
  echo $! >"$STATE/backend.pid"
  wait_for "$API_URL/health" 200 90 "backend"
}

start_web() {
  log "starting web app on :$WEB_PORT (vite preview of the production build)"
  # `exec` makes the subshell *become* vite, so the PID recorded is the server's.
  (cd "$APP_DIR/web" && VITE_DEV_API_TARGET="$API_URL" exec nohup ./node_modules/.bin/vite preview \
    --host 127.0.0.1 --port "$WEB_PORT" --strictPort) >>"$LOGS/web.log" 2>&1 &
  echo $! >"$STATE/web.pid"
  wait_for "$WEB_URL/" 200 60 "web app"
  # Browser tests reach the API through the web origin's /api proxy.
  wait_for "$WEB_URL/api/auth/refresh" 401 30 "web → API proxy" POST
}

# api_json <method> <path> [json-body] → prints "<status>\n<body>"
api_json() {
  local method=$1 path=$2 body=${3:-}
  if [[ -n "$body" ]]; then
    curl -s -w '\n%{http_code}' -X "$method" -H 'Content-Type: application/json' --data-binary @- "$API_URL$path" <<<"$body"
  else
    curl -s -w '\n%{http_code}' -X "$method" "$API_URL$path"
  fi
}

login_role() {
  local out status
  out=$(api_json POST /api/auth/login "$(jq -n --arg e "$ADMIN_EMAIL" --arg p "$ADMIN_PASSWORD" '{email:$e,password:$p}')")
  status=$(tail -n1 <<<"$out")
  if [[ "$status" == 200 ]]; then sed '$d' <<<"$out" | jq -r '.user.globalRole'; else echo "HTTP_$status"; fi
}

# Super admin = an address in SUPER_ADMIN_EMAILS that is *verified* when the
# backend starts (MembershipService.bootstrapSuperAdmins). Achieved through
# real endpoints only: register, read the verification mail from Mailpit,
# redeem it, restart the backend so startup promotes the account.
bootstrap_super_admin() {
  local role; role=$(login_role)
  if [[ "$role" == SUPER_ADMIN ]]; then log "super admin ready ($ADMIN_EMAIL)"; return 0; fi

  if [[ "$role" == HTTP_401 ]]; then
    log "registering super admin $ADMIN_EMAIL"
    local out status
    out=$(api_json POST /api/auth/register \
      "$(jq -n --arg e "$ADMIN_EMAIL" --arg p "$ADMIN_PASSWORD" '{email:$e,password:$p,fullName:"E2E Super Admin",languagePreference:"en"}')")
    status=$(tail -n1 <<<"$out")
    [[ "$status" == 201 ]] || die "super admin registration answered $status (409 = exists with another password; try 'down -v')"
  elif [[ "$role" != USER ]]; then
    die "super admin login answered $role"
  fi

  log "reading verification mail from Mailpit"
  local id="" token=""
  for _ in {1..30}; do
    id=$(curl -s --get --data-urlencode "query=to:\"$ADMIN_EMAIL\"" "$MAILPIT_URL/api/v1/search" | jq -r '.messages[0].ID // empty')
    [[ -n "$id" ]] && break
    sleep 1
  done
  [[ -n "$id" ]] || die "no verification mail for $ADMIN_EMAIL in Mailpit; see $LOGS/backend.log"
  token=$(curl -s "$MAILPIT_URL/api/v1/message/$id" | jq -r '.Text' | grep -oE 'verify-email\?token=[^[:space:]]+' | head -n1 | sed 's/.*token=//')
  [[ -n "$token" ]] || die "verification mail has no token link"
  local out status
  out=$(api_json POST /api/auth/verify-email "$(jq -n --arg t "$token" '{token:$t}')")
  status=$(tail -n1 <<<"$out")
  [[ "$status" == 200 ]] || die "verify-email answered $status"

  log "restarting backend so startup promotes the verified address"
  stop_pid backend
  start_backend
  role=$(login_role)
  [[ "$role" == SUPER_ADMIN ]] || die "after restart $ADMIN_EMAIL is '$role', not SUPER_ADMIN"
  log "super admin ready ($ADMIN_EMAIL)"
}

write_env_file() {
  umask 077
  cat >"$ENV_FILE" <<EOF
# Generated by scripts/e2e-env.sh — local only, never commit.
E2E_API_URL=$API_URL
E2E_WEB_URL=$WEB_URL
E2E_MAILPIT_URL=$MAILPIT_URL
E2E_SUPERADMIN_EMAIL=$ADMIN_EMAIL
E2E_SUPERADMIN_PASSWORD=$ADMIN_PASSWORD
EOF
}

cmd_up() {
  [[ -n "$APP_DIR" && -d "$APP_DIR/backend" && -d "$APP_DIR/web" ]] \
    || die "app checkout not found; set E2E_APP_DIR (looked in ${E2E_APP_DIR:-$ROOT/../beauty_client_management})"
  command -v jq >/dev/null || die "jq is required"
  mkdir -p "$LOGS"

  ADMIN_EMAIL="${E2E_SUPERADMIN_EMAIL:-e2e-superadmin@example.test}"
  local saved=""
  [[ -f "$ENV_FILE" ]] && saved=$(sed -n 's/^E2E_SUPERADMIN_PASSWORD=//p' "$ENV_FILE")
  ADMIN_PASSWORD="${E2E_SUPERADMIN_PASSWORD:-${saved:-$(openssl rand -hex 16)}}"

  for port in "$API_PORT" "$WEB_PORT"; do
    if port_in_use "$port"; then
      [[ -f "$STATE/backend.pid" || -f "$STATE/web.pid" ]] && die "already running? try 'scripts/e2e-env.sh down' first"
      die "port $port is in use (a dev server?). Stop it or set E2E_API_PORT / E2E_WEB_PORT"
    fi
  done

  log "starting Postgres and Mailpit"
  compose up -d --wait

  ensure_java17
  # Saved before anything can fail, so a rerun reuses the same password.
  write_env_file
  if [[ "${E2E_SKIP_BUILD:-}" != 1 ]]; then
    log "building backend (installDist) from $APP_DIR"
    (cd "$APP_DIR/backend" && ./gradlew --no-daemon -q installDist) >>"$LOGS/build.log" 2>&1 \
      || die "backend build failed; see $LOGS/build.log"
    log "building web app"
    (cd "$APP_DIR/web" && { [[ -d node_modules ]] || npm ci --no-audit --no-fund; } && npm run build) >>"$LOGS/build.log" 2>&1 \
      || die "web build failed; see $LOGS/build.log"
  fi

  start_backend
  start_web
  bootstrap_super_admin
  log "ready — run: ./gradlew test"
}

cmd_down() {
  stop_pid web
  stop_pid backend
  if [[ "${1:-}" == -v ]]; then
    compose down -v
    # The super admin lived in the deleted database.
    rm -f "$ENV_FILE"
  else
    compose down
  fi
}

cmd_status() {
  for name in backend web; do
    if [[ -f "$STATE/$name.pid" ]] && kill -0 "$(cat "$STATE/$name.pid")" 2>/dev/null; then
      log "$name running (pid $(cat "$STATE/$name.pid"))"
    else
      log "$name not running"
    fi
  done
  compose ps
}

case "${1:-}" in
  up) cmd_up ;;
  down) shift; cmd_down "$@" ;;
  status) cmd_status ;;
  *) echo "usage: $0 up | down [-v] | status" >&2; exit 2 ;;
esac
