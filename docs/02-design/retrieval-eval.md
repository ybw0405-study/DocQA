# 检索质量评估 harness（eval）

对 RAG 检索链路做可回归的离线评测，产出 Recall@k / MRR / nDCG@k 三个标准指标。

## 为什么需要它

检索质量（「用户问的这个问题，正确的文档块有没有被召回到前 k 位」）是 RAG 系统的
核心指标，但它过去只在人工验证时临时跑过，没有固化成可回归的基线——任何对
embedding / rerank / 融合策略的改动都无法自动化地发现检索质量是否回退。

本 harness 补上这条回归线：一条命令、确定性可复现、已接入 CI。

## 运行

```bash
cd ai-python
python -m docqa_ai.eval          # 人类可读表格报告
python -m docqa_ai.eval --json   # 机器可读 JSON（供脚本/CI 消费）
```

## 离线基线（默认）

默认走 `OfflineEmbeddingProvider`（确定性特征哈希）+ `InMemoryVectorRepository`，
**不依赖网络、不依赖真实 API key**，结果逐字节可复现，适合 CI 防回归。

离线哈希不是语义模型，它的价值在于：
- 锁住「检索链路没回归」（跨主题、真实条文等简单查询应 100% 命中 Recall@1）；
- 区分「词面可召回」与「语义需理解」（同主题细粒度查询在离线哈希下 Recall@1 偏低
  是**已知且合理**的，见 `eval/corpus.py` 注释）。

## 真实 provider（测生产语义质量）

设置环境变量后，用真实 embedding / rerank 跑生产语义模型的真实质量数字：

```bash
export DOCQA_EVAL_EMBEDDING=openai
export DOCQA_EMBEDDING_BASE_URL=https://...
export DOCQA_EMBEDDING_API_KEY=sk-...   # 不写默认密钥，不落盘到项目
export DOCQA_EMBEDDING_MODEL=text-embedding-v3

export DOCQA_EVAL_RERANK=openai
export DOCQA_RERANK_BASE_URL=https://...
export DOCQA_RERANK_API_KEY=sk-...
export DOCQA_RERANK_MODEL=gte-rerank-v2

python -m docqa_ai.eval
```

## 语料与指标

语料为「合成 + 真实」混合（`eval/corpus.py`）：

| 类别 | 内容 | 查询数 |
|---|---|---|
| 合成 · 跨主题 | 8 个互不重叠主题（经济/医学/历史/…） | 8 |
| 合成 · 同主题细粒度 | 劳动法 6 块，压排序质量 | 5 |
| 真实 · 政策条文 | 劳动法/民法典/个税法/道交法条文 | 4 |

指标（`eval/metrics.py`）：

- **Recall@k**：前 k 个结果里命中相关块的比例（召回能力）；
- **MRR**：第一个相关块名次的倒数均值（排序质量）；
- **nDCG@k**：归一化折损累计增益（兼顾名次的排序质量）。

ground truth 用 `(document_id, chunk_index)` 唯一标识一个块，避免跨文档语料下仅按
chunk_index 撞键。

## 实测基线（2026-11-21）

同一份语料、同一组查询，离线哈希 vs 真实语义模型的对比：

| 指标 | 离线哈希 embedding | text-embedding-v3 + gte-rerank-v2 |
|---|---|---|
| Recall@1 | 0.853 | **0.971** |
| Recall@3 | 1.000 | 1.000 |
| MRR | 0.941 | **1.000** |
| nDCG@1 | 0.882 | **1.000** |

决定性证据在同主题细粒度：`labor-q1`（「试用期多长？」）与 `labor-q2`
（「经济补偿怎么算？」）在离线哈希下 Recall@1 = 0（词面匹配无法区分同主题相近块），
真实语义模型下 Recall@1 = 1.0 —— 精确印证了「离线哈希测链路不回归、真实模型测语义
质量」的分工。唯一残留的 `labor-q5`（两个相关块）R@1=0.5 是**合理**的：它有两个
ground-truth 块，命中其一即 0.5，且 MRR=1.0 说明第一相关块仍排第一。

## 回归测试

`tests/test_eval_harness.py` 锁住离线基线的下限（query_count 不漂移、简单查询 R@1=1.0、
整体 Recall@3=1.0），随 `pytest` 在 CI 的 Python job 自动运行。
