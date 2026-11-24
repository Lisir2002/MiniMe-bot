#!/usr/bin/env bash
set -euo pipefail

INSTALL_DIR="/opt/minibot"
CONFIG_DIR="/etc/minibot"
DATA_DIR="/var/lib/minibot"
SERVICE_FILE="minibot-control.service"
BINARY=""
BINARY_LIB_DIR=""
DIST_URL=""
DIST_SHA256=""
DIST_TMP_DIR=""
BOOTSTRAP=""
SERVER_NAME="${MINIBOT_SERVER_NAME:-Mini-AIBOT Cloud}"
CONTROL_HOST="${MINIBOT_HOST:-}"
CONTROL_PORT="${MINIBOT_PORT:-}"
DB_URL="${MINIBOT_DB_URL:-}"
DB_USER="${MINIBOT_DB_USER:-minibot}"
DB_PASSWORD="${MINIBOT_DB_PASSWORD:-}"
POSTGRES_CONTAINER="minibot-postgres"
DB_PASSWORD_FILE="$CONFIG_DIR/db.password"
MINIBOT_UID="${MINIBOT_UID:-1000}"
MINIBOT_GID="${MINIBOT_GID:-1000}"
REGISTRY_HOST="${MINIBOT_REGISTRY_HOST:-}"
REGISTRY_USERNAME="${MINIBOT_REGISTRY_USERNAME:-}"
REGISTRY_TOKEN="${MINIBOT_REGISTRY_TOKEN:-}"
ACCESS_MODE="${MINIBOT_ACCESS_MODE:-}"
DOMAIN="${MINIBOT_DOMAIN:-}"
ACME_EMAIL="${MINIBOT_ACME_EMAIL:-}"
ACCESS_MODE_EXPLICIT=""
DOMAIN_EXPLICIT=""
CADDY_VERSION="${MINIBOT_CADDY_VERSION:-2.11.9}"

cleanup() {
  if [[ -n "$DIST_TMP_DIR" ]]; then
    rm -rf "$DIST_TMP_DIR"
  fi
}
trap cleanup EXIT

# Preserve existing registry credentials when reinstalling without passing them again.
if [[ -f "$CONFIG_DIR/control.env" ]]; then
  [[ -z "$CONTROL_HOST" ]] && CONTROL_HOST="$(sed -n 's/^MINIBOT_HOST=//p' "$CONFIG_DIR/control.env" | head -n1 || true)"
  [[ -z "$CONTROL_PORT" ]] && CONTROL_PORT="$(sed -n 's/^MINIBOT_PORT=//p' "$CONFIG_DIR/control.env" | head -n1 || true)"
  [[ -z "$REGISTRY_HOST" ]] && REGISTRY_HOST="$(sed -n 's/^MINIBOT_REGISTRY_HOST=//p' "$CONFIG_DIR/control.env" | head -n1 || true)"
  [[ -z "$REGISTRY_USERNAME" ]] && REGISTRY_USERNAME="$(sed -n 's/^MINIBOT_REGISTRY_USERNAME=//p' "$CONFIG_DIR/control.env" | head -n1 || true)"
  [[ -z "$REGISTRY_TOKEN" ]] && REGISTRY_TOKEN="$(sed -n 's/^MINIBOT_REGISTRY_TOKEN=//p' "$CONFIG_DIR/control.env" | head -n1 || true)"
  [[ -z "$ACCESS_MODE" ]] && ACCESS_MODE="$(sed -n 's/^MINIBOT_ACCESS_MODE=//p' "$CONFIG_DIR/control.env" | head -n1 || true)"
  [[ -z "$DOMAIN" ]] && DOMAIN="$(sed -n 's/^MINIBOT_DOMAIN=//p' "$CONFIG_DIR/control.env" | head -n1 || true)"
  [[ -z "$ACME_EMAIL" ]] && ACME_EMAIL="$(sed -n 's/^MINIBOT_ACME_EMAIL=//p' "$CONFIG_DIR/control.env" | head -n1 || true)"
fi

usage() {
  cat <<'EOF'
Usage: install.sh (--binary <path> | --dist-url <url>) [options]

Options:
  --binary <path>        Local minibot-control executable.
  --dist-url <url>       Download minibot-control-dist.tar.gz from this URL.
  --dist-sha256 <hex>    Expected SHA-256 of the distribution archive.
  --bootstrap <token>    One-time pairing token. Generated when omitted.
  --server-name <name>   Display name for this server.
  --db-url <jdbc-url>    Use an existing PostgreSQL instead of the bundled container.
  --db-user <user>
  --db-password <password>
  --domain <fqdn>        Enable HTTPS with this domain (Caddy + automatic certificate).
  --acme-email <email>   Optional ACME account email used by Caddy.
  --access ip            Explicitly enable the plain HTTP IP test mode.
  --help
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --binary) BINARY="${2:-}"; shift 2 ;;
    --dist-url) DIST_URL="${2:-}"; shift 2 ;;
    --dist-sha256) DIST_SHA256="${2:-}"; shift 2 ;;
    --bootstrap) BOOTSTRAP="${2:-}"; shift 2 ;;
    --server-name) SERVER_NAME="${2:-}"; shift 2 ;;
    --db-url) DB_URL="${2:-}"; shift 2 ;;
    --db-user) DB_USER="${2:-}"; shift 2 ;;
    --db-password) DB_PASSWORD="${2:-}"; shift 2 ;;
    --domain) DOMAIN="${2:-}"; ACCESS_MODE="domain"; DOMAIN_EXPLICIT="1"; shift 2 ;;
    --acme-email) ACME_EMAIL="${2:-}"; shift 2 ;;
    --access)
      case "${2:-}" in
        ip) ACCESS_MODE="ip"; ACCESS_MODE_EXPLICIT="1"; DOMAIN="" ;;
        *) echo "--access only accepts 'ip'; use --domain for HTTPS." >&2; exit 1 ;;
      esac
      shift 2
      ;;
    --help|-h) usage; exit 0 ;;
    *) echo "Unknown argument: $1" >&2; usage; exit 1 ;;
  esac
done

if [[ "$ACCESS_MODE_EXPLICIT" == "1" && "$DOMAIN_EXPLICIT" == "1" ]]; then
  echo "--domain and --access ip cannot be used together." >&2
  exit 1
fi

if [[ -z "$ACCESS_MODE" ]]; then
  ACCESS_MODE="ip"
fi
if [[ "$ACCESS_MODE" != "ip" && "$ACCESS_MODE" != "domain" ]]; then
  echo "MINIBOT_ACCESS_MODE must be 'ip' or 'domain'." >&2
  exit 1
fi

normalize_domain() {
  local value="$1"
  value="$(printf '%s' "$value" | tr '[:upper:]' '[:lower:]' | sed 's#^https\?://##; s#/*$##')"
  printf '%s' "$value"
}

if [[ "$ACCESS_MODE" == "domain" ]]; then
  if [[ -z "$DOMAIN" ]]; then
    echo "--domain <fqdn> is required for domain access mode." >&2
    exit 1
  fi
  DOMAIN="$(normalize_domain "$DOMAIN")"
  if [[ ! "$DOMAIN" =~ ^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$ ]]; then
    echo "Invalid domain: $DOMAIN" >&2
    exit 1
  fi
  if [[ "${#DOMAIN}" -gt 253 ]]; then
    echo "Domain is longer than 253 characters." >&2
    exit 1
  fi
  IFS='.' read -r -a DOMAIN_LABELS <<< "$DOMAIN"
  for label in "${DOMAIN_LABELS[@]}"; do
    if [[ "${#label}" -gt 63 ]]; then
      echo "Domain label is longer than 63 characters: $label" >&2
      exit 1
    fi
  done
  CONTROL_HOST="127.0.0.1"
elif [[ "$ACCESS_MODE_EXPLICIT" == "1" ]]; then
  CONTROL_HOST="0.0.0.0"
fi

if [[ -n "$BINARY" && -n "$DIST_URL" ]]; then
  echo "--binary and --dist-url cannot be used together." >&2
  exit 1
fi
if [[ -z "$BINARY" && -z "$DIST_URL" ]]; then
  echo "A control-plane binary is required: --binary <path> or --dist-url <url>" >&2
  exit 1
fi
if [[ -n "$DIST_URL" ]]; then
  if [[ ! "$DIST_URL" =~ ^https:// ]]; then
    echo "--dist-url must use HTTPS." >&2
    exit 1
  fi
  if ! command -v curl >/dev/null 2>&1 || ! command -v tar >/dev/null 2>&1 || ! command -v sha256sum >/dev/null 2>&1; then
    echo "curl, tar and sha256sum are required for --dist-url." >&2
    exit 1
  fi
fi
if [[ -n "$BINARY" && ! -x "$BINARY" ]]; then
  echo "The --binary path is not executable: $BINARY" >&2
  exit 1
fi

if [[ "$(id -u)" -ne 0 ]]; then
  echo "This installer must run as root." >&2
  exit 1
fi

if ! command -v systemctl >/dev/null 2>&1; then
  echo "systemd is required." >&2
  exit 1
fi
if ! command -v docker >/dev/null 2>&1; then
  echo "Docker is required. Install Docker first." >&2
  exit 1
fi
if ! command -v openssl >/dev/null 2>&1; then
  echo "openssl is required." >&2
  exit 1
fi
if [[ "$ACCESS_MODE" == "domain" ]] && ! command -v curl >/dev/null 2>&1; then
  echo "curl is required for domain mode (Caddy download)." >&2
  exit 1
fi
if ! docker info >/dev/null 2>&1; then
  echo "Docker daemon is not reachable." >&2
  exit 1
fi

download_distribution() {
  DIST_TMP_DIR="$(mktemp -d)"
  local archive expected actual
  archive="$DIST_TMP_DIR/minibot-control-dist.tar.gz"
  echo "Downloading control-plane distribution..."
  curl -fsSL --retry 3 -o "$archive" "$DIST_URL"
  if [[ -z "$DIST_SHA256" ]]; then
    local sha_file="$DIST_TMP_DIR/dist.sha256"
    curl -fsSL --retry 3 -o "$sha_file" "${DIST_URL}.sha256"
    expected="$(awk 'NR==1 {print $1}' "$sha_file")"
  else
    expected="${DIST_SHA256#sha256:}"
  fi
  expected="$(printf '%s' "$expected" | tr '[:upper:]' '[:lower:]')"
  if [[ ! "$expected" =~ ^[0-9a-f]{64}$ ]]; then
    echo "Invalid distribution SHA-256: $expected" >&2
    exit 1
  fi
  actual="$(sha256sum "$archive" | awk '{print $1}')"
  if [[ "$actual" != "$expected" ]]; then
    echo "Distribution checksum mismatch." >&2
    echo "expected: $expected" >&2
    echo "actual:   $actual" >&2
    exit 1
  fi
  tar -xzf "$archive" -C "$DIST_TMP_DIR"
  BINARY="$DIST_TMP_DIR/minibot-control/bin/minibot-control"
  BINARY_LIB_DIR="$DIST_TMP_DIR/minibot-control/lib"
  if [[ ! -x "$BINARY" ]]; then
    echo "The distribution does not contain minibot-control/bin/minibot-control." >&2
    exit 1
  fi
  echo "Distribution checksum verified."
}

install_caddy() {
  if ! command -v caddy >/dev/null 2>&1; then
    local arch
    case "$(uname -m)" in
      x86_64|amd64) arch="amd64" ;;
      aarch64|arm64) arch="arm64" ;;
      *) echo "Unsupported architecture for Caddy: $(uname -m)" >&2; exit 1 ;;
    esac
    local tmp_dir archive checksums
    tmp_dir="$(mktemp -d)"
    archive="caddy_${CADDY_VERSION}_linux_${arch}.tar.gz"
    checksums="caddy_${CADDY_VERSION}_checksums.txt"
    echo "Installing Caddy ${CADDY_VERSION} (${arch})..."
    curl -fsSL -o "$tmp_dir/$archive" \
      "https://github.com/caddyserver/caddy/releases/download/v${CADDY_VERSION}/${archive}"
    curl -fsSL -o "$tmp_dir/$checksums" \
      "https://github.com/caddyserver/caddy/releases/download/v${CADDY_VERSION}/${checksums}"
    if ! (cd "$tmp_dir" && grep "  ${archive}$" "$checksums" | sha512sum -c - >/dev/null 2>&1); then
      echo "Caddy checksum verification failed." >&2
      rm -rf "$tmp_dir"
      exit 1
    fi
    tar -xzf "$tmp_dir/$archive" -C "$tmp_dir" caddy
    install -m 0755 "$tmp_dir/caddy" /usr/local/bin/caddy
    rm -rf "$tmp_dir"
  fi

  if ! id caddy >/dev/null 2>&1; then
    useradd --system --home-dir /var/lib/caddy --shell /usr/sbin/nologin caddy
  fi
  install -d -m 0750 -o caddy -g caddy /var/lib/caddy
  install -d -m 0750 -o caddy -g caddy /etc/caddy
  cat > /etc/systemd/system/caddy.service <<'CADDY_UNIT'
[Unit]
Description=Mini-AIBOT Caddy reverse proxy
Documentation=https://caddyserver.com/docs/
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=caddy
Group=caddy
ExecStart=/usr/local/bin/caddy run --config /etc/caddy/Caddyfile --adapter caddyfile
ExecReload=/usr/local/bin/caddy reload --config /etc/caddy/Caddyfile --adapter caddyfile
AmbientCapabilities=CAP_NET_BIND_SERVICE
CapabilityBoundingSet=CAP_NET_BIND_SERVICE
NoNewPrivileges=true
ProtectSystem=full
ProtectHome=true
ReadWritePaths=/var/lib/caddy /etc/caddy
Restart=on-failure
RestartSec=3

[Install]
WantedBy=multi-user.target
CADDY_UNIT
  chmod 0644 /etc/systemd/system/caddy.service
  systemctl daemon-reload
}

configure_caddy_domain() {
  install_caddy
  local caddyfile
  caddyfile="/etc/caddy/Caddyfile"
  {
    echo "{"
    echo "    admin off"
    if [[ -n "$ACME_EMAIL" ]]; then
      echo "    email $ACME_EMAIL"
    fi
    echo "}"
    echo
    echo "${DOMAIN} {"
    echo "    reverse_proxy 127.0.0.1:${CONTROL_PORT}"
    echo "}"
  } > "$caddyfile"
  chown caddy:caddy "$caddyfile"
  chmod 0640 "$caddyfile"
  systemctl enable caddy >/dev/null
  if systemctl is-active --quiet caddy; then
    systemctl reload caddy
  else
    systemctl start caddy
  fi
}

disable_caddy_domain() {
  if systemctl list-unit-files caddy.service >/dev/null 2>&1; then
    systemctl disable --now caddy >/dev/null 2>&1 || true
  fi
  rm -f /etc/caddy/Caddyfile
}

if getent group "$MINIBOT_GID" >/dev/null 2>&1 && [[ "$(getent group "$MINIBOT_GID" | cut -d: -f1)" != "minibot" ]]; then
  echo "GID $MINIBOT_GID is already used by another group; set MINIBOT_GID to a free GID that matches the base image." >&2
  exit 1
fi
if getent passwd "$MINIBOT_UID" >/dev/null 2>&1 && [[ "$(getent passwd "$MINIBOT_UID" | cut -d: -f1)" != "minibot" ]]; then
  echo "UID $MINIBOT_UID is already used by another user; set MINIBOT_UID to a free UID that matches the base image." >&2
  exit 1
fi
if ! getent group minibot >/dev/null 2>&1; then
  groupadd --system --gid "$MINIBOT_GID" minibot
fi
if ! id minibot >/dev/null 2>&1; then
  useradd --system --uid "$MINIBOT_UID" --gid minibot --create-home --home-dir "$DATA_DIR" --shell /usr/sbin/nologin minibot
fi
if [[ "$(id -u minibot)" != "$MINIBOT_UID" || "$(id -g minibot)" != "$MINIBOT_GID" ]]; then
  echo "Existing minibot user UID/GID is $(id -u minibot):$(id -g minibot), expected $MINIBOT_UID:$MINIBOT_GID." >&2
  echo "Align it with the base image, or set MINIBOT_UID/MINIBOT_GID." >&2
  exit 1
fi
if getent group docker >/dev/null 2>&1; then
  usermod -aG docker minibot
fi

if [[ -n "$DIST_URL" ]]; then
  download_distribution
fi
if [[ ! -x "$BINARY" ]]; then
  echo "Control-plane executable is missing or not executable: $BINARY" >&2
  exit 1
fi
if [[ -z "$BINARY_LIB_DIR" ]]; then
  local_bin_dir="$(cd "$(dirname "$BINARY")" && pwd)"
  if [[ -d "$local_bin_dir/../lib" ]]; then
    BINARY_LIB_DIR="$local_bin_dir/../lib"
  fi
fi

install -d -m 0755 "$INSTALL_DIR/bin"
install -d -m 0750 "$CONFIG_DIR"
install -d -m 0750 "$DATA_DIR"
install -d -m 0750 "$DATA_DIR/postgres"
install -m 0755 "$BINARY" "$INSTALL_DIR/bin/minibot-control"
if [[ -n "$BINARY_LIB_DIR" ]]; then
  if [[ ! -d "$BINARY_LIB_DIR" ]]; then
    echo "Control-plane library directory is missing: $BINARY_LIB_DIR" >&2
    exit 1
  fi
  rm -rf "$INSTALL_DIR/lib.new"
  cp -a "$BINARY_LIB_DIR" "$INSTALL_DIR/lib.new"
  rm -rf "$INSTALL_DIR/lib"
  mv "$INSTALL_DIR/lib.new" "$INSTALL_DIR/lib"
fi

if [[ -z "$DB_URL" ]]; then
  if [[ -f "$DB_PASSWORD_FILE" ]]; then
    DB_PASSWORD="$(cat "$DB_PASSWORD_FILE")"
  elif [[ -z "$DB_PASSWORD" ]]; then
    DB_PASSWORD="$(openssl rand -hex 24)"
  fi
  if [[ ! -f "$DB_PASSWORD_FILE" ]]; then
    install -m 0600 /dev/null "$DB_PASSWORD_FILE"
    printf '%s' "$DB_PASSWORD" > "$DB_PASSWORD_FILE"
  fi

  if ! docker inspect "$POSTGRES_CONTAINER" >/dev/null 2>&1; then
    docker run -d \
      --name "$POSTGRES_CONTAINER" \
      --restart unless-stopped \
      -e POSTGRES_DB=minibot \
      -e POSTGRES_USER="$DB_USER" \
      -e POSTGRES_PASSWORD="$DB_PASSWORD" \
      -p 127.0.0.1:5432:5432 \
      -v "$DATA_DIR/postgres:/var/lib/postgresql/data" \
      postgres:16
  fi

  echo "Waiting for PostgreSQL..."
  for _ in $(seq 1 60); do
    if docker exec "$POSTGRES_CONTAINER" pg_isready -U "$DB_USER" -d minibot >/dev/null 2>&1; then
      break
    fi
    sleep 1
  done
  DB_URL="jdbc:postgresql://127.0.0.1:5432/minibot"
fi

if [[ -z "$BOOTSTRAP" ]]; then
  BOOTSTRAP="$(openssl rand -hex 24)"
fi

: "${CONTROL_HOST:=127.0.0.1}"
: "${CONTROL_PORT:=8787}"

if [[ "$ACCESS_MODE" == "domain" ]]; then
  PUBLIC_URL="https://${DOMAIN}"
else
  PUBLIC_URL=""
fi

cat > "$CONFIG_DIR/control.env" <<EOF
MINIBOT_HOST=$CONTROL_HOST
MINIBOT_PORT=$CONTROL_PORT
MINIBOT_DB_URL=$DB_URL
MINIBOT_DB_USER=$DB_USER
MINIBOT_DB_PASSWORD=$DB_PASSWORD
MINIBOT_SERVER_NAME=$SERVER_NAME
MINIBOT_TIMEZONE=Asia/Shanghai
MINIBOT_DATA_DIR=$DATA_DIR
MINIBOT_MASTER_KEY_PATH=$DATA_DIR/master.key
MINIBOT_BOOTSTRAP_TOKEN_FILE=$DATA_DIR/bootstrap.token
MINIBOT_PAIRING_TTL_SECONDS=3600
EOF
if [[ -n "$REGISTRY_HOST" ]]; then
  cat >> "$CONFIG_DIR/control.env" <<EOF
MINIBOT_REGISTRY_HOST=$REGISTRY_HOST
MINIBOT_REGISTRY_USERNAME=$REGISTRY_USERNAME
MINIBOT_REGISTRY_TOKEN=$REGISTRY_TOKEN
EOF
fi
cat >> "$CONFIG_DIR/control.env" <<EOF
MINIBOT_ACCESS_MODE=$ACCESS_MODE
MINIBOT_DOMAIN=$DOMAIN
MINIBOT_PUBLIC_URL=$PUBLIC_URL
MINIBOT_ACME_EMAIL=$ACME_EMAIL
EOF
chmod 0600 "$CONFIG_DIR/control.env"
cat > "$CONFIG_DIR/access.env" <<EOF
MINIBOT_ACCESS_MODE=$ACCESS_MODE
MINIBOT_PUBLIC_URL=$PUBLIC_URL
MINIBOT_DOMAIN=$DOMAIN
MINIBOT_ACME_EMAIL=$ACME_EMAIL
EOF
chmod 0640 "$CONFIG_DIR/access.env"
printf '%s' "$BOOTSTRAP" > "$DATA_DIR/bootstrap.token"
chmod 0600 "$DATA_DIR/bootstrap.token"

cat > "/etc/systemd/system/$SERVICE_FILE" <<'CONTROL_UNIT'
[Unit]
Description=Mini-AIBOT cloud control plane
After=network-online.target docker.service
Wants=network-online.target
Requires=docker.service

[Service]
Type=simple
User=minibot
Group=minibot
EnvironmentFile=/etc/minibot/control.env
ExecStart=/opt/minibot/bin/minibot-control
Restart=on-failure
RestartSec=5
TimeoutStopSec=30
LimitNOFILE=65535

[Install]
WantedBy=multi-user.target
CONTROL_UNIT
chmod 0644 "/etc/systemd/system/$SERVICE_FILE"

chown -R minibot:minibot "$DATA_DIR"
chown -R root:minibot "$CONFIG_DIR"
chmod 0640 "$CONFIG_DIR/control.env"
chmod 0640 "$CONFIG_DIR/db.password" 2>/dev/null || true

systemctl daemon-reload
systemctl enable "$SERVICE_FILE" >/dev/null
systemctl restart "$SERVICE_FILE"

if [[ "$ACCESS_MODE" == "domain" ]]; then
  configure_caddy_domain
else
  disable_caddy_domain
fi

cat <<EOF

Mini-AIBOT control plane installed.

Run the App against:
  ${PUBLIC_URL:-http://<this-server-ip>:${CONTROL_PORT}}

One-time pairing token:
  $BOOTSTRAP

The token file is:
  $DATA_DIR/bootstrap.token

It is removed after the first device is paired.
EOF
