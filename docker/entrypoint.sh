#!/bin/bash

APP_JAR="/app/app.jar"
DATA_DIR="/data"
PG_DATA="$DATA_DIR/postgres"
PG_LOG="$PG_DATA/pg.log"
APP_LOG="$DATA_DIR/app.log"
CONFIG_FILE="$DATA_DIR/config.env"
JWT_SECRET_FILE="$DATA_DIR/.jwt_secret"

die() { echo "ERROR: $1" >&2; exit 1; }
log() { echo "  $1"; }

# ── Set defaults for in-container PostgreSQL ──────────
export SPRING_DATASOURCE_URL="${SPRING_DATASOURCE_URL:-jdbc:postgresql://localhost:5432/novel_agent}"
export SPRING_DATASOURCE_USERNAME="${SPRING_DATASOURCE_USERNAME:-novel}"
export SPRING_DATASOURCE_PASSWORD="${SPRING_DATASOURCE_PASSWORD:-novel123}"

# ── Generate JWT secret on first run ──────────────────
if [ ! -f "$JWT_SECRET_FILE" ]; then
    cat /dev/urandom 2>/dev/null | tr -dc 'a-zA-Z0-9' | head -c 48 > "$JWT_SECRET_FILE" 2>/dev/null || true
    [ -s "$JWT_SECRET_FILE" ] || echo "change-me-$(date +%s)-$$" > "$JWT_SECRET_FILE"
fi
export APP_JWT_SECRET="$(cat $JWT_SECRET_FILE)"

# ── First-run: initialize PostgreSQL ──────────────────
if [ ! -f "$PG_DATA/PG_VERSION" ]; then
    echo ""
    echo ">>> First run: initializing PostgreSQL..."

    mkdir -p "$PG_DATA" /var/run/postgresql || die "Cannot create directories"
    chown postgres:postgres "$PG_DATA" /var/run/postgresql 2>/dev/null || true

    log "Running initdb..."
    su postgres -c "/usr/lib/postgresql/16/bin/initdb -D $PG_DATA" > /dev/null 2>&1 \
        || su - postgres -c "/usr/lib/postgresql/16/bin/initdb -D $PG_DATA" > /dev/null 2>&1 \
        || die "initdb failed"
    echo "listen_addresses = '127.0.0.1'" >> "$PG_DATA/postgresql.conf"
    log "initdb OK"

    log "Starting PostgreSQL for setup..."
    su postgres -c "/usr/lib/postgresql/16/bin/pg_ctl -D $PG_DATA -l $PG_LOG -w start" \
        || die "Cannot start PostgreSQL"
    log "PostgreSQL started"

    # Wait for PostgreSQL to be ready to accept connections
    log "Waiting for PostgreSQL to accept connections..."
    for i in $(seq 1 10); do
        if su - postgres -c "/usr/lib/postgresql/16/bin/psql -c 'SELECT 1;'" > /dev/null 2>&1; then
            break
        fi
        sleep 1
    done

    log "Creating user & database..."
    su - postgres -c "/usr/lib/postgresql/16/bin/psql -c \"CREATE USER novel WITH PASSWORD 'novel123';\"" \
        || die "Failed to create user 'novel'"
    su - postgres -c "/usr/lib/postgresql/16/bin/psql -c \"CREATE DATABASE novel_agent OWNER novel;\"" \
        || die "Failed to create database 'novel_agent'"
    su - postgres -c "/usr/lib/postgresql/16/bin/psql -d novel_agent -c \"CREATE EXTENSION IF NOT EXISTS vector;\"" \
        || die "Failed to create vector extension"
    su - postgres -c "/usr/lib/postgresql/16/bin/psql -d novel_agent -c \"CREATE EXTENSION IF NOT EXISTS pgcrypto;\"" \
        || die "Failed to create pgcrypto extension"

    log "Running SQL migrations..."
    for f in /app/sql/*.sql; do
        echo "    $(basename $f)"
        su - postgres -c "/usr/lib/postgresql/16/bin/psql -d novel_agent -f $f" \
            || die "Migration failed: $(basename $f)"
    done

    # Grant permissions to novel user (tables created by postgres user)
    su - postgres -c "/usr/lib/postgresql/16/bin/psql -d novel_agent -c 'GRANT ALL ON ALL TABLES IN SCHEMA public TO novel; GRANT ALL ON ALL SEQUENCES IN SCHEMA public TO novel;'" \
        || die "Failed to grant permissions"

    log "Stopping setup PostgreSQL..."
    su - postgres -c "/usr/lib/postgresql/16/bin/pg_ctl -D $PG_DATA stop" \
        || die "Failed to stop PostgreSQL after setup"
    echo ">>> Database initialized."
fi

# ── Start PostgreSQL ──────────────────────────────────
echo "Starting PostgreSQL..."

# Fix permissions (Docker volumes can lose ownership between container recreations)
chown -R postgres:postgres "$PG_DATA" 2>/dev/null || true

# Remove stale PID file from unclean shutdown (docker rm -f)
rm -f "$PG_DATA/postmaster.pid"

su - postgres -c "/usr/lib/postgresql/16/bin/pg_ctl -D $PG_DATA -l $PG_LOG -w start" \
    || die "Cannot start PostgreSQL"
echo "  PostgreSQL: OK"

# ── Self-healing: ensure role & database exist (for restarts where first-run init failed) ──
if ! su - postgres -c "/usr/lib/postgresql/16/bin/psql -c \"SELECT 1 FROM pg_roles WHERE rolname='novel'\" -t" 2>/dev/null | grep -q 1; then
    echo "  Re-creating missing role 'novel'..."
    su - postgres -c "/usr/lib/postgresql/16/bin/psql -c \"CREATE USER novel WITH PASSWORD 'novel123';\"" \
        || die "Failed to create user 'novel'"
fi
if ! su - postgres -c "/usr/lib/postgresql/16/bin/psql -c \"SELECT 1 FROM pg_database WHERE datname='novel_agent'\" -t" 2>/dev/null | grep -q 1; then
    echo "  Re-creating missing database 'novel_agent'..."
    su - postgres -c "/usr/lib/postgresql/16/bin/psql -c \"CREATE DATABASE novel_agent OWNER novel;\"" \
        || die "Failed to create database 'novel_agent'"
    su - postgres -c "/usr/lib/postgresql/16/bin/psql -d novel_agent -c \"CREATE EXTENSION IF NOT EXISTS vector;\"" 2>/dev/null || true
    su - postgres -c "/usr/lib/postgresql/16/bin/psql -d novel_agent -c \"CREATE EXTENSION IF NOT EXISTS pgcrypto;\"" 2>/dev/null || true
    # Re-run migrations if db was recreated
    for f in /app/sql/*.sql; do
        su - postgres -c "/usr/lib/postgresql/16/bin/psql -d novel_agent -f $f" 2>/dev/null || true
    done
    su - postgres -c "/usr/lib/postgresql/16/bin/psql -d novel_agent -c 'GRANT ALL ON ALL TABLES IN SCHEMA public TO novel; GRANT ALL ON ALL SEQUENCES IN SCHEMA public TO novel;'" 2>/dev/null || true
fi

# ── Start Redis ───────────────────────────────────────
echo -n "  Redis:      starting..."
redis-server --daemonize yes --bind 127.0.0.1 --port 6379 2>/dev/null
echo " OK"

# ── Start SearXNG ─────────────────────────────────────
echo -n "  SearXNG:    starting..."
sed -i "s/change-me-at-startup/$APP_JWT_SECRET/" /etc/searxng/settings.yml
export SEARXNG_SETTINGS_PATH=/etc/searxng/settings.yml
PYTHONPATH=/opt/searxng-src /opt/searxng/bin/python -c "
import searx.webapp
searx.webapp.app.run(host='127.0.0.1', port=8088, debug=False)
" 2>"$DATA_DIR/searxng.log" &
SEARXNG_PID=$!
# Wait for SearXNG to be ready
for i in $(seq 1 10); do
    if curl -sf http://127.0.0.1:8088/ > /dev/null 2>&1; then
        echo " OK"
        break
    fi
    sleep 1
done
if ! kill -0 $SEARXNG_PID 2>/dev/null; then
    echo " FAILED (web search won't work)"
fi

# ── Load config (if exists) ───────────────────────────
if [ -f "$CONFIG_FILE" ]; then
    echo "  Config:     found"
    set -a; . "$CONFIG_FILE"; set +a
else
    echo "  Config:     not set (run 'onboard' to configure)"
fi

# ── Start Spring Boot ─────────────────────────────────
echo -n "  Spring Boot: starting..."
java -Xms128m -Xmx384m -XX:+UseG1GC -XX:MaxMetaspaceSize=128m -jar "$APP_JAR" > "$APP_LOG" 2>&1 &
APP_PID=$!

# Wait for app to be ready
READY=0
for i in $(seq 1 30); do
    sleep 2
    if curl -sf http://localhost:8080/ > /dev/null 2>&1; then
        READY=1
        echo " OK"
        break
    fi
    if ! kill -0 $APP_PID 2>/dev/null; then
        echo " FAILED"
        echo "  Check logs: docker exec novel-agent cat /data/app.log"
        break
    fi
done
if [ $READY -eq 0 ] && kill -0 $APP_PID 2>/dev/null; then
    echo " (still starting, check /data/app.log)"
fi

# ── Welcome ──────────────────────────────────────────
echo ""
echo "========================================"
echo "  Novel Agent"
echo "  http://localhost:8080"
echo ""
echo "  Commands:"
echo "    onboard   Configure API keys"
echo "    exit      Stop all services"
echo "========================================"
echo ""

# ── Cleanup on exit ──────────────────────────────────
cleanup() {
    echo ""
    echo "Stopping..."
    kill $SEARXNG_PID 2>/dev/null || true
    redis-cli shutdown 2>/dev/null || true
    kill $APP_PID 2>/dev/null || true
    wait $APP_PID 2>/dev/null || true
    su postgres -c "/usr/lib/postgresql/16/bin/pg_ctl -D $PG_DATA stop" 2>/dev/null || true
    echo "Bye!"
}
trap cleanup EXIT

# ── Interactive shell or wait for app ─────────────────
if [ -t 0 ]; then
    # Running with TTY (docker run -it) — give user a shell
    exec /bin/bash
else
    # Running detached (docker run -d) — wait for app to finish
    wait $APP_PID
fi
