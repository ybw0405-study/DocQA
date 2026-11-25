-- 管理员在界面配置的模型连接（生成模型）。事实源：docs/02-design/storage.md §2.19，
-- 代码侧由 MigrationV13ContractTest 逐字对账。
--
-- 设计要点：
-- 1. 凭据只存密文：credential 是 AES-256-GCM 的 12B IV || ciphertext || 16B tag，
-- AAD 绑定 connectionId|tenantId，密文搬到别的行或别的租户下解不开。
-- credential_key_id 记录封装时主密钥的 keyId，轮换后按它从 keyring 选旧密钥。
-- 两列必须同时为空或同时非空（chk_model_connection_credential_pair），
-- 避免出现「有密文却没 keyId」这种永远解不开的死行。
-- 2. 每租户至多一条生效连接，由存储层保证而不是靠应用自觉：MySQL 没有部分索引，
-- 用 STORED 生成列 active_slot 把「enabled 且 is_active」投影成 1、其余投影成 NULL，
-- 再对 (tenant_id, active_slot) 建唯一键——NULL 不参与唯一性，因此非生效行可以任意多。
-- 切换生效必须在一个事务里先清旧再设新，否则唯一键会直接拒绝。
-- 3. name 在租户内唯一，创建走 upsert：管理面沿用 D-197「不声明幂等头、按值幂等」，
-- 同名重复提交收敛到同一行，而不是建出第二条连接。
-- 4. 探测到的模型清单是瞬态 UI 数据，不落库；这里只留 probe_status 与 last_probed_at，
-- 让管理面能区分「从未探测」「探测通过」「探测失败」三态，而不是把失败显示成未配置。
-- 5. updated_by 不建外键：管理员身份以 audit_event 为准，与 ai_quota 同一约定。
-- 6. 本迁移是纯 DDL（0 条 INSERT/UPDATE/DELETE），迁移账号 ss_migrator 没有业务表写权限。

CREATE TABLE model_connection (
  id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  name VARCHAR(64) NOT NULL,
  preset_id VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL,
  base_url VARCHAR(512) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  model VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  is_active BOOLEAN NOT NULL DEFAULT FALSE,
  active_slot TINYINT GENERATED ALWAYS AS (IF(enabled = 1 AND is_active = 1, 1, NULL)) STORED,
  credential VARBINARY(1024) NULL,
  credential_key_id CHAR(12) CHARACTER SET ascii COLLATE ascii_bin NULL,
  probe_status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'UNKNOWN',
  last_probed_at DATETIME(3) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                       ON UPDATE CURRENT_TIMESTAMP(3),
  updated_by CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_model_connection_tenant_name (tenant_id, name),
  UNIQUE KEY uk_model_connection_tenant_active (tenant_id, active_slot)
    COMMENT '每租户至多一条生效连接；非生效行 active_slot 为 NULL，不参与唯一性',
  KEY idx_model_connection_tenant_created (tenant_id, created_at, id)
    COMMENT '管理面按租户列出连接并 keyset 分页',
  CONSTRAINT fk_model_connection_tenant FOREIGN KEY (tenant_id)
    REFERENCES tenant (id) ON DELETE CASCADE,
  CONSTRAINT chk_model_connection_base_url CHECK (
    base_url LIKE 'https://%' OR base_url LIKE 'http://%'
  ),
  CONSTRAINT chk_model_connection_model CHECK (CHAR_LENGTH(model) >= 1),
  CONSTRAINT chk_model_connection_probe_status CHECK (
    probe_status IN ('UNKNOWN','OK','FAILED')
  ),
  CONSTRAINT chk_model_connection_credential_pair CHECK (
    (credential IS NULL) = (credential_key_id IS NULL)
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
  COMMENT='管理员配置的生成模型连接；凭据只存 AES-256-GCM 密文，每租户至多一条生效';
