CREATE TABLE document (
  id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  user_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  file_name VARCHAR(255) NOT NULL,
  content_type VARCHAR(127) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  size_bytes BIGINT UNSIGNED NOT NULL,
  md5 CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  object_key VARCHAR(512) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'UPLOADED',
  retry_count TINYINT UNSIGNED NOT NULL DEFAULT 0,
  max_retry TINYINT UNSIGNED NOT NULL DEFAULT 3,
  error_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  error_message VARCHAR(512) NULL,
  index_version VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'v1',
  parsing_started_at DATETIME(3) NULL,
  indexed_at DATETIME(3) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_document_user_md5 (user_id, md5),
  UNIQUE KEY uk_document_object_key (object_key),
  KEY idx_document_status_parse (status, parsing_started_at),
  KEY idx_document_user_created (user_id, created_at),
  CONSTRAINT chk_document_size CHECK (size_bytes > 0 AND size_bytes <= 52428800),
  CONSTRAINT chk_document_retry CHECK (retry_count <= max_retry),
  CONSTRAINT chk_document_status CHECK (
    status IN ('UPLOADED', 'PARSING', 'INDEXED', 'FAILED', 'DEAD')
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
  COMMENT='文档控制面元数据；状态机与重试计数由 document-service 维护';

CREATE TABLE chunk (
  id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  document_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  chunk_index INT UNSIGNED NOT NULL,
  vector_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  content_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  token_count INT UNSIGNED NOT NULL,
  index_version VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_chunk_document_version_index (document_id, index_version, chunk_index),
  UNIQUE KEY uk_chunk_vector (vector_id),
  CONSTRAINT fk_chunk_document FOREIGN KEY (document_id)
    REFERENCES document (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
  COMMENT='分块元数据；正文与 embedding 在 PGVector，避免双写正文';
