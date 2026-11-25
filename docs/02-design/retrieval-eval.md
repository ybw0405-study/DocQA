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
| 真实 · 政策条文 | 劳动法/民法典/个税法/道交法条文（含同法条细粒度对：饮酒 vs 醉酒、补偿 vs 双倍赔偿、共同债务认定 vs 离婚清偿、税率 vs 减除费用） | 8 |
| 跨文档 · hard negative | 劳动合同实务 / 交通事故指南（**无查询**，只作为候选池干扰，压跨文档召回） | 0 |
| 负例 · 文档无答案 | 工伤认定 / 发明专利 / 公积金贷款（正确行为是空返回） | 3 |

所有查询（含正例与负例）都在**全语料 scope** 上检索：候选池混入干扰文档，单文档 scope
下永远撞不到它们，跨文档才暴露召回/排序是否被稀释。

指标（`eval/metrics.py`）：

- **Recall@k**：前 k 个结果里命中相关块的比例（召回能力）；
- **MRR**：第一个相关块名次的倒数均值（排序质量）；
- **nDCG@k**：归一化折损累计增益（兼顾名次的排序质量）；
- **负例误召回率（观察指标）**：按 `_NEGATIVE_MIN_SCORE=0.5` 过滤后仍命中块的比例，
  `top_score` 反映检索层对「有/无答案」的区分度。**不进基线门禁**——无答案的最终判定
  在 nightly LLM judge 层，检索层负例只观察分数分布（离线哈希 vs 真实语义分数分布不同）。

ground truth 用 `(document_id, chunk_index)` 唯一标识一个块，避免跨文档语料下仅按
chunk_index 撞键。

## 实测基线（2026-11-25，21 正例 + 3 负例，跨文档）

同一份语料、同一组查询在**全语料 scope（含 2 份跨文档干扰）**下检索，离线哈希 vs
真实语义模型的对比（2026-11-25 补跨文档 hard negative 与负例后重测；上一轮单文档
scope 数字见 git 历史）：

| 指标 | 离线哈希 embedding | text-embedding-v3 + gte-rerank-v2 |
|---|---|---|
| Recall@1 | 0.786 | **0.929** |
| Recall@3 | 1.000 | 1.000 |
| MRR | 0.889 | **0.976** |
| nDCG@1 | 0.810 | **0.952** |
| nDCG@5 | 0.917 | **0.979** |
| 负例误召回率（观察） | 0/3（top_score 0.10~0.22） | 1/3（top_score 0.40~0.63） |

跨文档 hard negative 的效果是**真实且可复现**的：`labor-q2`（「经济补偿怎么算？」）
的正确块与干扰文档 `distractor-labor` 的「主动辞职无需补偿」语义高度重叠，真实语义
模型 + gte-rerank-v2 的 Recall@1 从单文档的 1.0 掉到 0（正确块落 top2，MRR=0.5）。
这暴露的是 rerank 在「同一话题下两个相邻子问题」上的区分边界，正是单文档 scope
永远测不到的 RAG 核心难点。`labor-q1`（「试用期多长？」无强干扰）仍是 R@1=1.0。

负例误召回率是**观察指标**而非门禁：真实语义模型会把「工伤认定」与语料里的医疗期/
交通事故块语义关联，top_score 抬到 0.63（离线哈希只有 0.22）。这印证「无答案」的最终
判定必须交给 LLM judge 层，检索层的分数只能作粗筛，不能作结论。

## 回归测试

`tests/test_eval_harness.py` 锁住离线基线的下限（query_count 不漂移、简单查询 R@1=1.0、
整体 Recall@3=1.0），随 `pytest` 在 CI 的 Python job 自动运行。
