# ─── Stage 1: Build frontend ──────────────────────────
FROM node:20-alpine AS frontend-build
WORKDIR /app
COPY frontend/package*.json ./
RUN npm ci
COPY frontend/ .
RUN npm run build

# ─── Stage 2: Build backend with frontend embedded ──────
FROM maven:3.9-eclipse-temurin-17-alpine AS backend-build
WORKDIR /app
COPY settings.xml /root/.m2/settings.xml
COPY pom.xml .
RUN mvn dependency:go-offline -q || true
COPY src ./src
COPY --from=frontend-build /app/dist ./src/main/resources/static
RUN mvn package -DskipTests -q

# ─── Stage 3: All-in-one runtime ───────────────────────
FROM ubuntu:22.04
ENV DEBIAN_FRONTEND=noninteractive

# 1. Add PostgreSQL + deadsnakes repos (build-only tools discarded after use)
RUN apt-get update && apt-get install -y --no-install-recommends \
    wget ca-certificates gnupg lsb-release software-properties-common \
    && wget -qO- https://www.postgresql.org/media/keys/ACCC4CF8.asc \
    | gpg --dearmor -o /usr/share/keyrings/postgresql-archive-keyring.gpg \
    && echo "deb [signed-by=/usr/share/keyrings/postgresql-archive-keyring.gpg] http://apt.postgresql.org/pub/repos/apt $(lsb_release -cs)-pgdg main" \
    > /etc/apt/sources.list.d/pgdg.list \
    && add-apt-repository -y ppa:deadsnakes/ppa \
    && apt-get update \
    && apt-get install -y --no-install-recommends \
        postgresql-16 postgresql-16-pgvector \
        openjdk-17-jre-headless \
        python3.11 python3.11-venv \
        redis-server procps curl git \
        locales ca-certificates \
    && locale-gen en_US.UTF-8 \
    && apt-get purge -y gnupg lsb-release software-properties-common \
    && apt-get autoremove -y --purge \
    && rm -rf /var/lib/apt/lists/* /var/cache/apt/archives/* \
            /usr/share/doc/* /usr/share/man/* /usr/share/info/* \
    && find /usr/share/locale -mindepth 1 -maxdepth 1 ! -name 'en' ! -name 'en_US' -exec rm -rf {} +

# 2. Install SearXNG (web search engine)
# Pre-downloaded zip → extract → pip install -r requirements.txt → run from source.
# Zip is pre-downloaded to docker/searxng-master.zip to avoid GitHub network issues.
RUN python3.11 -m venv /opt/searxng \
    && /opt/searxng/bin/pip install --upgrade --no-cache-dir -i https://pypi.tuna.tsinghua.edu.cn/simple --trusted-host pypi.tuna.tsinghua.edu.cn pip setuptools wheel
COPY docker/searxng-master.zip /tmp/searxng-master.zip
RUN python3.11 -c "import zipfile; zipfile.ZipFile('/tmp/searxng-master.zip').extractall('/opt')" \
    && mv /opt/searxng-master /opt/searxng-src \
    && rm /tmp/searxng-master.zip \
    && /opt/searxng/bin/pip install --no-cache-dir -i https://pypi.tuna.tsinghua.edu.cn/simple --trusted-host pypi.tuna.tsinghua.edu.cn -r /opt/searxng-src/requirements.txt \
    && find /opt/searxng /opt/searxng-src -name '__pycache__' -exec rm -rf {} + 2>/dev/null; true \
    && find /opt/searxng /opt/searxng-src -name '*.pyc' -delete 2>/dev/null; true \
    && rm -rf /root/.cache \
    && mkdir -p /etc/searxng \
    && echo "VERSION_STRING = '2025.11.24+allinone'" > /opt/searxng-src/searx/version_frozen.py \
    && echo "VERSION_TAG = '2025.11.24+allinone'" >> /opt/searxng-src/searx/version_frozen.py \
    && echo "DOCKER_TAG = 'allinone'" >> /opt/searxng-src/searx/version_frozen.py \
    && echo "GIT_URL = 'https://github.com/searxng/searxng'" >> /opt/searxng-src/searx/version_frozen.py \
    && echo "GIT_BRANCH = 'master'" >> /opt/searxng-src/searx/version_frozen.py

ENV LANG=en_US.UTF-8 \
    LC_ALL=en_US.UTF-8

# Create required directories
RUN mkdir -p /app /data /var/run/postgresql \
    && chown postgres:postgres /var/run/postgresql

# Copy application jar (has frontend embedded in /static)
COPY --from=backend-build /app/target/*.jar /app/app.jar

# Copy SQL migrations
COPY sql/*.sql /app/sql/

# Copy SearXNG config
COPY docker/searxng/settings.yml /etc/searxng/settings.yml

# Copy scripts
COPY docker/entrypoint.sh /entrypoint.sh
COPY docker/onboard.sh /usr/local/bin/onboard
RUN chmod +x /entrypoint.sh /usr/local/bin/onboard

VOLUME /data
WORKDIR /app
EXPOSE 8080
ENTRYPOINT ["/entrypoint.sh"]
