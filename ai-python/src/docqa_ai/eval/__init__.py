"""DocQA 检索质量评估 harness。

对 RAG 检索链路做可回归的离线评测：Recall@k / MRR / nDCG@k。
默认走 OfflineEmbeddingProvider（确定性特征哈希）+ InMemoryVectorRepository，
不依赖网络与真实 API key，结果可复现、可进 CI；也可通过环境变量切换到真实 provider
测生产语义模型的质量。

入口：
    python -m docqa_ai.eval                          # 离线检索基线
    python -m docqa_ai.eval --suite all --check-baseline   # 检索 + agent 治理套件门禁
    python -m docqa_ai.eval --json                   # 输出机器可读 JSON
"""

from docqa_ai.eval.metrics import compute_metrics, evaluate_queries
from docqa_ai.eval.runner import run_eval

__all__ = ["compute_metrics", "evaluate_queries", "run_eval"]
