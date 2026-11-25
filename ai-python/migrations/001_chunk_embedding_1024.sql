-- PGVector collection for the 1024-dimension embedding model.
-- Applied by the AI data-plane owner before starting the parse worker.
-- The table name must match DOCQA_AI_PG_TABLE (default chunk_embedding_1024).

CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE IF NOT EXISTS chunk_embedding_1024 (
  vector_id      UUID PRIMARY KEY,
  document_id    UUID NOT NULL,
  chunk_index    INT NOT NULL CHECK (chunk_index >= 0),
  content        TEXT NOT NULL,
  embedding      vector(1024) NOT NULL,
  metadata       JSONB NOT NULL DEFAULT '{}'::jsonb,
  index_version  TEXT NOT NULL,
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (document_id, index_version, chunk_index)
);

CREATE INDEX IF NOT EXISTS idx_chunk_embedding_document
  ON chunk_embedding_1024 (document_id);

CREATE INDEX IF NOT EXISTS idx_chunk_embedding_document_version
  ON chunk_embedding_1024 (document_id, index_version);

CREATE INDEX IF NOT EXISTS idx_chunk_embedding_hnsw
  ON chunk_embedding_1024
  USING hnsw (embedding vector_cosine_ops)
  WITH (m = 16, ef_construction = 64);

COMMENT ON TABLE chunk_embedding_1024 IS
  'Document chunks and 1024-dimension embeddings; Python writes, RAG reads by authorized document scope.';
COMMENT ON COLUMN chunk_embedding_1024.vector_id IS
  'UUIDv5 over documentId, indexVersion and chunkIndex; stable across retries and mirrored by MySQL chunk.vector_id.';
COMMENT ON COLUMN chunk_embedding_1024.metadata IS
  'page_no, token_count, content_hash, embedding_model and file_name; never used for authorization.';
