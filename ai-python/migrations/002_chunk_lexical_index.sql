-- 词法召回通道；事实源：docs/02-design/storage.md §4.4。
--
-- 只建一个表达式 GIN 索引：零新列、零新表、零数据改写，HNSW 向量索引不动。
-- 上一版方案是 tsvector STORED 生成列，被实测推翻——加生成列会重写整张堆表并**全量重建** idx_chunk_embedding_hnsw
-- （表 relfilenode 17320→17330、t_hnsw 17327→17336，E7）。chunk 行数由 chunk_size_chars=800 与 50 MiB 文件上限决定，
-- 是无界的，所以那是一次长时阻塞操作，且此后每次 content 变更都要重写整行。表达式索引把这份代价换成
-- 「每行插入多算一次表达式」与「重检与投影各多算一次」两条有界小账（§4.4.3）。
--
-- 下面这段表达式与 ai-python/src/docqa_ai/text/lexical.py 的 LEXICAL_TSVECTOR_SQL 必须逐字一致，
-- 由 tests/test_migrations.py 对账，不是口头约定。两个细节都是语义的一部分，写错不报错、只是静默失去召回：
--   1) 方括号：'(一-鿿)' 作为 POSIX 正则匹配的是字面三字符序列，不是任何汉字，空格插不进去，
--      simple 解析器就会把整段中文粘成一个词位（E18），按单字发出的查询永不命中；
--   2) 第 4 个参数 'g'：少写它就只替换首个匹配，索引内容与正文脱节，且计划从 Bitmap Index Scan 退化成 Seq Scan（E8/E21）。
-- 字符类只覆盖 U+4E00–U+9FFF。扩展 A 区（U+3400–U+4DBF）与兼容表意区（U+F900–U+FAFF）的汉字
-- 既不会被拆成单字，查询侧也一律按分隔符丢弃，因此「生僻字精确术语」在纯词法形态下是结构性缺失，
-- 不报错、不降级、不产生观测信号，能观察它的只有真实标注集。
--
-- 没有按用户过滤的列或索引：词法通道读的是同一张表、同一套授权入参 (document_id, index_version)，
-- 授权范围由 Java 侧结论原样喂进来（红线），对 001 生效的「迁移文本里不得出现按用户过滤的列」断言同样覆盖本文件。
--
-- 不安装任何扩展：pg_trgm 虽 available 但实测不能作为召回通道（LIKE '%学习%' 不走 gin_trgm_ops，
-- 三个相似算子命中 0 / 1613 / 0 而子串真值 8979，E10）；中文分词器 zhparser / pg_jieba 在本镜像
-- pg_available_extensions 里根本不存在（E11），真分词器归 S6。

CREATE INDEX IF NOT EXISTS idx_chunk_embedding_lexical
  ON chunk_embedding_1024
  USING gin (
    to_tsvector('simple'::regconfig,
                regexp_replace(content, '([一-鿿])', ' \1 ', 'g'))
 );

COMMENT ON INDEX idx_chunk_embedding_lexical IS
  'Lexical recall channel. Expression GIN over content with CJK chars space-padded; no new column, no HNSW impact. Authorization stays on (document_id, index_version); no per-user column exists in PG.';
