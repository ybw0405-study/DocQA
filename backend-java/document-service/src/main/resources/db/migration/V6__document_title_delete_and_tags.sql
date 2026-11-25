-- S2 文档域补全：展示标题、双版本重建、软删除位、向量行数确认与用户级标签词表。
-- 事实源：docs/02-design/storage.md §2.2（document）、§2.14（document_tag）、§2.15（document_tag_link）。

ALTER TABLE document
  ADD COLUMN title VARCHAR(120) NULL
    COMMENT '用户可改的展示标题；展示口径 title ?? file_name，file_name 保持不可变' AFTER file_name,
  ADD COLUMN pending_index_version VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL
    COMMENT '重建在途的目标版本；命中它的 parse.completed 才推进 index_version' AFTER index_version,
  ADD COLUMN vector_rows INT UNSIGNED NULL
    COMMENT '向量侧最后一次确认的行数，由 Python 同事务统计后回投；NULL 表示从未确认' AFTER pending_index_version,
  ADD COLUMN vector_confirmed_at DATETIME(3) NULL
    COMMENT '该确认发生的时刻，与 vector_rows 同批写入；不是向量侧实时状态' AFTER vector_rows,
  ADD COLUMN deleted_at DATETIME(3) NULL
    COMMENT '软删除位；非空即对所有读路径不可见，历史引用与审计归属仍依赖本行' AFTER indexed_at,
  ADD KEY idx_document_deleted_vector (deleted_at, vector_confirmed_at)
    COMMENT '对账扫描：已软删除但向量侧尚未确认的文档，补投 vector-change-requested';

CREATE TABLE document_tag (
  id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  user_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  name VARCHAR(24) NOT NULL,
  normalized_name VARCHAR(24) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_document_tag_user_normalized (user_id, normalized_name)
    COMMENT '标签名在同一用户内唯一，是 PUT 全量替换的收敛依据',
  CONSTRAINT chk_document_tag_name CHECK (CHAR_LENGTH(normalized_name) BETWEEN 1 AND 24)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
  COMMENT='用户级标签词表；name 保存首次创建时的展示写法，normalized_name 保存归一后的判等键';

CREATE TABLE document_tag_link (
  document_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  tag_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (document_id, tag_id),
  KEY idx_document_tag_link_tag (tag_id, document_id)
    COMMENT '按标签过滤文档列表（搜索的 tag 参数）',
  CONSTRAINT fk_document_tag_link_document FOREIGN KEY (document_id)
    REFERENCES document (id) ON DELETE CASCADE,
  CONSTRAINT fk_document_tag_link_tag FOREIGN KEY (tag_id)
    REFERENCES document_tag (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
  COMMENT='文档与标签的多对多关系；PUT 全量替换，增量 diff 不在服务端做';
