-- S6 真实模型向量空间分表；决策面是 docs/06-expansion/phase6-scope.md §1.7.2 的 D-99 / D-100 / D-101 与 §1.9 的 D-117。
--
-- ⚠️ 执行状态：本文件在 2026-11-02 立项时**从未对任何数据库执行**；§1.8 的 DDL 放行当时只覆盖 `002_chunk_lexical_index.sql`。
-- **2026-11-05 16:29 已由 D-226 放行后在 `docqa_vec`（PG 16.15 / pgvector 0.8.7）执行**：
-- 8 列、6 个索引（pkey + UNIQUE + document + document_version + hnsw + lexical）、0 行、`embedding = vector(1024)`，
-- 执行前后对账见 `docs/03-review/cr-log-s11-e2e.md`「003_* DDL 执行记录」。本文件全部语句为 `IF NOT EXISTS`，
-- 重复执行不改变结果；**未 INSERT、未搬运旧表 28 行、未切换 `DOCQA_AI_PG_TABLE`**。
-- 空表上的 `EXPLAIN (ANALYZE, BUFFERS)` 与 GIN/HNSW 命中取证属 S11-5，不在本次执行范围内。
-- 初版说明保留如下：本轮交付的是迁移文本 + `tests/test_migrations.py` 的对账断言。
--
-- 为什么要分表（D-99 采纳 ②）：`metadata->>'embedding_model'` 是一个**只写不读**的键（唯一写点
-- `parse_worker/worker.py`，`src` 全量无读取点），同表覆盖等于把两个向量空间塞进同一个 `vector_cosine_ops` HNSW，
-- 事后既不能按模型分区召回，也没有任何观测信号——HNSW 与列绑定，加不了「事后 filter」（D-100 否定 ① 的理由正是
-- 后置过滤会破坏 `ORDER BY embedding <=> %s LIMIT k` 的 top-k 语义）。换模型的唯一诚实形态是换表。
--
-- 本表承载的向量空间由**两件事**共同定义，缺一个就不再是同一种空间：
--   1) 模型：DashScope `text-embedding-v4`，输出 1024 维（E42 / E43 实测「请求不带 dimensions 时默认 1024」，
--      D-101 保持硬绑：模型给不出 1024 维就不接，而不是松绑维度）；
--   2) 预处理口径：写侧在文本进入 embedding Provider **之前**执行 `mask_text`（D-110 ②）。
-- 第 2 条意味着本表与 `chunk_embedding_1024`（offline 空间，输入为原文）**不可互灌**：同一模型下掩码前后
-- 的向量不相等，混在一起就等于把 D-99 要消除的静默混合换个地方重演一次。既有 28 行继续留在原表，
-- 本文件不 INSERT、不搬运、不改写任何旧行（D-99 否定 ③「清空重灌」：那要重跑全部解析并破坏
-- `vector_id` 的稳定派生与 MySQL `chunk.vector_id` 的镜像关系）。
--
-- 表名必须与运行配置一致：`DOCQA_AI_PG_TABLE`（`config.py` 的 `pg_table`，默认 `chunk_embedding_1024`）。
-- 读侧两条通道与写侧投递都从配置拿表名（`api/main.py`、`parse_worker/worker.py`、`parse_worker/vector_worker.py`），
-- 所以切空间是「配置 + 本迁移」，零 SQL 改动。切换 `embedding_model` 需要**新的迁移**（004…）而不是改本文件：
-- 表名里的模型标识就是这件事的可见形态，名字与模型对不上时人眼能看出来，启动期守卫（D-117）则拦住
-- 「真实 Provider + 默认 offline 表」这个最容易踩的组合。
--
-- 词法通道不能省（D-99 代价条目原话：少建一个是静默能力缺失）。下面这段表达式与
-- `text/lexical.py` 的 `LEXICAL_TSVECTOR_SQL` 逐字一致，且与本文件同目录的 002 那份**逐字同形**，
-- 由 `tests/test_migrations.py` 对账，不是口头约定。两个细节仍是语义的一部分，写错不报错、只是静默失去召回：
--   1) 方括号：'(一-鿿)' 作为 POSIX 正则匹配的是字面三字符序列，不是任何汉字；
--   2) 第 4 个参数 'g'：少写它就只替换首个匹配。
-- 字符类只覆盖 U+4E00–U+9FFF；扩展 A 区与兼容表意区的汉字在本通道零召回（D-96 / `S5-M3`），本表不改变这一点。
--
-- 三个索引名与 001 / 002 的全部索引名互不重复（PG 的索引名在 schema 内全局唯一），由 `test_migrations.py`
-- 跨三个文件对账；HNSW 参数与 001 一致（`m = 16`、`ef_construction = 64`），否则两个空间的召回语义不可比。
--
-- 授权面与 001 相同：没有任何按用户过滤的列，授权范围仍由 Java 侧结论以 (document_id, index_version) 传入（D-87 红线）。
--
-- D-100 ② 的混空间对账（**人工 / 运维脚本执行，不进请求路径、不进 CI**，结论只进文档并写明谓词）：
--   SELECT metadata->>'embedding_model' AS embedding_model, count(*)
--     FROM chunk_embedding_1024_text_embedding_v4
--    GROUP BY 1
--    ORDER BY 2 DESC;
-- 期望恰好一行；出现第二行即本表已被两个模型写入，按 D-99 的口径需要建新表重灌，而不是加过滤。

CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE IF NOT EXISTS chunk_embedding_1024_text_embedding_v4 (
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

CREATE INDEX IF NOT EXISTS idx_chunk_embedding_v4_document
  ON chunk_embedding_1024_text_embedding_v4 (document_id);

CREATE INDEX IF NOT EXISTS idx_chunk_embedding_v4_document_version
  ON chunk_embedding_1024_text_embedding_v4 (document_id, index_version);

CREATE INDEX IF NOT EXISTS idx_chunk_embedding_v4_hnsw
  ON chunk_embedding_1024_text_embedding_v4
  USING hnsw (embedding vector_cosine_ops)
  WITH (m = 16, ef_construction = 64);

CREATE INDEX IF NOT EXISTS idx_chunk_embedding_v4_lexical
  ON chunk_embedding_1024_text_embedding_v4
  USING gin (
    to_tsvector('simple'::regconfig,
                regexp_replace(content, '([一-鿿])', ' \1 ', 'g'))
  );

COMMENT ON TABLE chunk_embedding_1024_text_embedding_v4 IS
  'Real-model 1024-dimension vector space (DashScope text-embedding-v4) over mask_text-prefixed input; Python writes, RAG reads by authorized document scope. Not interchangeable with chunk_embedding_1024.';
COMMENT ON COLUMN chunk_embedding_1024_text_embedding_v4.vector_id IS
  'UUIDv5 over documentId, indexVersion and chunkIndex; stable across retries and mirrored by MySQL chunk.vector_id. Derivation is identical to the offline table, so the same chunk keeps the same id across spaces.';
COMMENT ON COLUMN chunk_embedding_1024_text_embedding_v4.content IS
  'Original document text. Only the embedding input is PII-masked (D-110); stored content stays unmasked because masking happens after recall on the read path.';
COMMENT ON COLUMN chunk_embedding_1024_text_embedding_v4.metadata IS
  'page_no, token_count, content_hash, embedding_model and file_name; never used for authorization. embedding_model is a written-only audit field, not a recall filter (D-99).';
