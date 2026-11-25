-- docker-entrypoint-initdb.d：仅建库骨架，表由 document-service 的 Flyway 迁移创建，
-- 账号授权由 ops/sql/least-privilege.sql.tmpl 在启动脚本中渲染重放。
-- 关键：库必须先于 start-dev.ps1 的授权重放存在——否则全新空卷启动时
-- 第 [4/8] 步会撞 ERROR 1049 Unknown database 'docqa' 直接中断。
CREATE DATABASE IF NOT EXISTS docqa CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
