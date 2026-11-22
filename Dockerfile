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
FROM maven:3.9-eclipse-temurin-26@sha256:b2c1ad85954592f9928e84327c65201f308ad9b5d8ed7d5b823717c97bf23fbb AS java-build
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
FROM python:3.15-slim@sha256:65a65fc7b30b06cb6df4930fd4650697cb23f4c2698ab81fc87e4b031bc36928 AS python-base
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
FROM nginx:1.29-alpine@sha256:5616878291a2eed594aee8db4dade5878cf7edcb475e59193904b198d9b830de AS frontend
COPY --from=frontend-build /src/frontend-vue/dist /usr/share/nginx/html
USER root
RUN chown -R 10001:10001 /usr/share/nginx/html /var/cache/nginx /var/log/nginx /var/run /tmp \
 && sed -i 's/listen  *80;/listen 8080;/' /etc/nginx/conf.d/default.conf
USER 10001:10001
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=5s --start-period=10s --retries=5 \
  CMD wget -q -O /dev/null http://127.0.0.1:8080/ || exit 1
ENTRYPOINT ["nginx", "-g", "daemon off; pid /tmp/nginx.pid;"]
