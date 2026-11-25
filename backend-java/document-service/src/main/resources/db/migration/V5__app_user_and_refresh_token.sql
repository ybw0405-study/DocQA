-- 身份与授权。
-- 迁移仍由 document-service 独占执行（schema owner 单一，避免同一 flyway_schema_history 上的并发迁移）。
CREATE TABLE app_user (
  id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  username VARCHAR(32) CHARACTER SET ascii COLLATE ascii_general_ci NOT NULL,
  password_hash CHAR(60) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'ACTIVE',
  last_login_at DATETIME(3) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_app_user_username (username),
  KEY idx_app_user_created (created_at, id),
  KEY idx_app_user_status_created (status, created_at, id),
  CONSTRAINT chk_app_user_status CHECK (status IN ('ACTIVE', 'DISABLED')),
  CONSTRAINT chk_app_user_hash_length CHECK (CHAR_LENGTH(password_hash) = 60)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
  COMMENT='用户控制面身份；口令只保存 BCrypt 摘要，任何 API 面都不外发该列';

-- username 用 ascii_general_ci 判重：应用侧写入前已小写归一，这里的大小写不敏感唯一键
-- 是第二道防线，防止归一规则被绕过（例如未来接入外部身份源时漏掉 normalize）。
CREATE TABLE app_user_role (
  user_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  role VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (user_id, role),
  CONSTRAINT chk_app_user_role CHECK (role IN ('USER', 'ADMIN')),
  CONSTRAINT fk_app_user_role_user FOREIGN KEY (user_id)
    REFERENCES app_user (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
  COMMENT='角色授予关系；多角色由多行表达，权限判定在应用层枚举内完成';

CREATE TABLE refresh_token (
  id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  user_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  family_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  token_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'ACTIVE',
  expires_at DATETIME(3) NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_refresh_token_hash (token_hash),
  KEY idx_refresh_token_user_status (user_id, status),
  KEY idx_refresh_token_family_status (family_id, status),
  KEY idx_refresh_token_expiry (expires_at),
  CONSTRAINT chk_refresh_token_status CHECK (status IN ('ACTIVE', 'USED', 'REVOKED')),
  CONSTRAINT fk_refresh_token_user FOREIGN KEY (user_id)
    REFERENCES app_user (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
  COMMENT='刷新令牌轮换链；只存 SHA-256 摘要，family_id 支撑复用检测时的整家族吊销';
