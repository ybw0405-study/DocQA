# syntax=docker/dockerfile:1
# DocQA 多阶段制品。构建 context = 仓库根目录，.dockerignore 已排除
# .venv / node_modules / target / .git 等。用法示例：
#   docker build --target java-service --build-arg JAR_PATH=/out/document-service.jar -t dq-document .
#   docker build --target ai-python -t dq-ai .
#   docker build --target frontend -t dq-frontend .
#
# 所有运行阶段均以非 root UID/GID 10001 运行；HEALTHCHECK 指向 readiness 端点：
#   Java 服务 -> GET /actuator/health/readiness（Spring Boot readiness 组，
#                document-service 纳入 db/redis/mq/minio 真实依赖探针）
#   Python 数据面 -> GET /health/ready（探 PG + Redis，依赖 down 返回 503）
#   前端静态站 -> GET /（入口 HTML 可返回即 ready）
# 基础镜像带 digest 的取值来自本机 docker image inspect 的真实 RepoDigests。

# ---------- 1. Java 构建 ----------
FROM maven:3.9-eclipse-temurin-17@sha256:1a352420f7aba21f5ad08df31bab55f74c013fb491f1ae8ab1dd7ff9ed698584 AS java-build
WORKDIR /src
COPY . .
WORKDIR /src/backend-java
RUN mkdir -p /out \
 && mvn -B -ntp -DskipTests -Dcheckstyle.skip package \
 && for m in user-service document-service reminder-service audit-service agent-gateway; do \
      cp "$(ls "$m"/target/"$m"-*.jar | grep -v '\.original$' | head -1)" "/out/$m.jar"; \
    done

# ---------- 2. 前端构建 ----------
FROM node:22-alpine@sha256:1314fb30ec37592d5845e5a160d522c0e680972aa534549940b8d73bde88481a AS frontend-build
WORKDIR /src/frontend-vue
COPY frontend-vue/package.json frontend-vue/package-lock.json ./
RUN npm ci
COPY frontend-vue/ ./
RUN npm run build

# ---------- 3. Java 运行时公共层（非 root + readiness 探针） ----------
FROM eclipse-temurin:17-jre-jammy@sha256:b3054a20e1a851574cdb1692dd0db5310c647e62214a2a7abecbec1b9e3ae8f1 AS java-base
USER root
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/*
WORKDIR /app
USER 10001:10001
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=5s --start-period=45s --retries=5 \
  CMD curl -fsS http://127.0.0.1:8080/actuator/health/readiness || exit 1

FROM java-base AS java-service
ARG JAR_PATH=/out/document-service.jar
COPY --from=java-build --chown=10001:10001 ${JAR_PATH} /app/app.jar
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

# ---------- 4. Python 数据面（锁文件 + --require-hashes） ----------
FROM python:3.12-slim@sha256:5e2bdb1b0d542d5039cffb8181c65930093949194a65c365dfb1b0fe13a4fa5b AS python-base
USER root
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/*
WORKDIR /app
EXPOSE 18000
HEALTHCHECK --interval=15s --timeout=5s --start-period=30s --retries=5 \
  CMD curl -fsS http://127.0.0.1:18000/health/ready || exit 1

FROM python-base AS ai-python
COPY ai-python/ ./
RUN pip install --no-cache-dir --require-hashes -r requirements.lock.txt \
 && pip install --no-cache-dir --no-deps -e .
USER 10001:10001
ENTRYPOINT ["python", "-m", "uvicorn", "docqa_ai.api.main:app", "--host", "0.0.0.0", "--port", "18000"]

# ---------- 5. 前端静态站 ----------
FROM nginx:1.27-alpine@sha256:65645c7bb6a0661892a8b03b89d0743208a18dd2f3f17a54ef4b76fb8e2f2a10 AS frontend
COPY --from=frontend-build /src/frontend-vue/dist /usr/share/nginx/html
USER root
RUN chown -R 10001:10001 /usr/share/nginx/html /var/cache/nginx /var/log/nginx /var/run /tmp \
 && sed -i 's/listen  *80;/listen 8080;/' /etc/nginx/conf.d/default.conf
USER 10001:10001
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=5s --start-period=10s --retries=5 \
  CMD wget -q -O /dev/null http://127.0.0.1:8080/ || exit 1
ENTRYPOINT ["nginx", "-g", "daemon off; pid /tmp/nginx.pid;"]
