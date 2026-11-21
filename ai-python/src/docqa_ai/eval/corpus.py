"""评测语料与查询集（合成 + 真实混合）。

设计目标：既覆盖「跨主题易区分」的粗粒度召回，也覆盖「同主题细粒度」的排序压力，
让 Recall@k 与 nDCG@k 都能在离线确定性 embedding 下得到有区分度的数字。

合成语料：8 个互不重叠的主题文档，每个主题拆成若干块，块间用词有明确差异，
保证特征哈希 embedding 能稳定区分。其中「劳动法」主题细分为 6 个相近块，
用于压排序质量（同主题内仅靠词面区分，难度更高）。

真实语料：从真实政策/法规文档抽取的片段（条款式），内容真实、可溯源。
"""

from __future__ import annotations

from dataclasses import dataclass


# (chunk_index, content) 语料块；同一文档内 chunk_index 唯一
@dataclass(frozen=True)
class CorpusDocument:
    document_id: str
    title: str
    chunks: tuple[tuple[int, str], ...]
    # 每条查询应命中的相关块集合
    queries: tuple[Query, ...] = ()


@dataclass(frozen=True)
class Query:
    query_id: str
    question: str
    relevant: tuple[int, ...]  # 相关 chunk_index 列表


# ---------------------------------------------------------------------------
# 合成语料：跨主题（8 主题，每主题 1 块）
# ---------------------------------------------------------------------------

_SYNTHETIC_TOPICS: list[tuple[str, str, str]] = [
    (
        "synthetic-economics",
        "经济学基础",
        "通货膨胀是指物价总水平持续上涨的现象，通常由货币供应量增长过快或需求过热引起。",
    ),
    (
        "synthetic-medicine",
        "医学常识",
        "高血压是一种慢性疾病，收缩压持续高于一百四十毫米汞柱需要药物干预和生活方式调整。",
    ),
    (
        "synthetic-history",
        "历史事件",
        "工业革命始于十八世纪的英国，蒸汽机的改良推动了纺织业和交通运输的机械化进程。",
    ),
    (
        "synthetic-cooking",
        "烹饪技巧",
        "制作红烧肉要先焯水去腥，再用冰糖炒糖色，最后小火慢炖四十分钟使肉质软烂入味。",
    ),
    (
        "synthetic-astronomy",
        "天文知识",
        "太阳系有八大行星，其中木星体积最大，土星以明显的光环结构著称。",
    ),
    (
        "synthetic-computer",
        "计算机原理",
        "操作系统负责管理进程调度与内存分配，进程是程序运行时的动态实例。",
    ),
    (
        "synthetic-music",
        "音乐理论",
        "五线谱由五条平行线组成，高音谱号标记在中音谱号上方，表示音高范围。",
    ),
    (
        "synthetic-geography",
        "地理知识",
        "长江是中国最长的河流，发源于青藏高原的唐古拉山脉，最终注入东海。",
    ),
]


def _synthetic_docs() -> list[CorpusDocument]:
    docs: list[CorpusDocument] = []
    for doc_id, title, content in _SYNTHETIC_TOPICS:
        qid = doc_id.removeprefix("synthetic-")
        docs.append(
            CorpusDocument(
                document_id=doc_id,
                title=title,
                chunks=((0, content),),
                queries=(
                    Query(query_id=f"{qid}-q1", question=content.split("，")[0] + "？", relevant=(0,)),
                ),
            )
        )
    return docs


# ---------------------------------------------------------------------------
# 合成语料：同主题细粒度（劳动法，6 块）
# ---------------------------------------------------------------------------

_LABOR_CHUNKS: tuple[tuple[int, str], ...] = (
    (0, "劳动者在试用期内被证明不符合录用条件的，用人单位可以解除劳动合同。"),
    (1, "经济补偿按劳动者在本单位工作的年限，每满一年支付一个月工资的标准向劳动者支付。"),
    (2, "用人单位安排劳动者延长工作时间的，应当支付不低于工资百分之一百五十的加班费。"),
    (3, "劳动者患病或者非因工负伤，在规定的医疗期内的，用人单位不得解除劳动合同。"),
    (4, "劳动合同期限三个月以上不满一年的，试用期不得超过一个月。"),
    (5, "用人单位未依法为劳动者缴纳社会保险费的，劳动者可以解除劳动合同并要求支付经济补偿。"),
)

_LABOR_QUERIES: tuple[Query, ...] = (
    Query("labor-q1", "试用期多长？", (4,)),
    Query("labor-q2", "经济补偿怎么算？", (1,)),
    Query("labor-q3", "加班费标准是多少？", (2,)),
    Query("labor-q4", "医疗期内能解除合同吗？", (3,)),
    Query("labor-q5", "什么情况下可以解除劳动合同？", (0, 5)),
)


def _labor_doc() -> CorpusDocument:
    return CorpusDocument(
        document_id="synthetic-labor",
        title="劳动法要点",
        chunks=_LABOR_CHUNKS,
        queries=_LABOR_QUERIES,
    )


# ---------------------------------------------------------------------------
# 真实语料：政策/法规条文片段（内容真实，可溯源）
# ---------------------------------------------------------------------------

_REAL_CHUNKS: tuple[tuple[int, str], ...] = (
    (
        0,
        "根据《中华人民共和国劳动合同法》第四十七条，经济补偿按劳动者在本单位工作的年限，"
        "每满一年支付一个月工资的标准向劳动者支付。六个月以上不满一年的，按一年计算；"
        "不满六个月的，向劳动者支付半个月工资的经济补偿。",
    ),
    (
        1,
        "《中华人民共和国民法典》第一千零六十四条规定，夫妻双方共同签名或者夫妻一方事后"
        "追认等共同意思表示所负的债务，属于夫妻共同债务。",
    ),
    (
        2,
        "《中华人民共和国个人所得税法》规定，综合所得适用百分之三至百分之四十五的超额累进税率。",
    ),
    (
        3,
        "《中华人民共和国道路交通安全法》第九十一条规定，饮酒后驾驶机动车的，处暂扣六个月"
        "机动车驾驶证，并处一千元以上二千元以下罚款。",
    ),
)

_REAL_QUERIES: tuple[Query, ...] = (
    Query("real-q1", "工作满六个月不满一年，经济补偿怎么算？", (0,)),
    Query("real-q2", "哪些债务属于夫妻共同债务？", (1,)),
    Query("real-q3", "个人所得税综合所得的税率是多少？", (2,)),
    Query("real-q4", "饮酒驾驶机动车怎么处罚？", (3,)),
)


def _real_docs() -> list[CorpusDocument]:
    return [
        CorpusDocument(
            document_id="real-legal",
            title="法律法规条文",
            chunks=_REAL_CHUNKS,
            queries=_REAL_QUERIES,
        )
    ]


def load_corpus() -> list[CorpusDocument]:
    """返回全部评测文档（合成 + 真实混合）。"""
    return [*_synthetic_docs(), _labor_doc(), *_real_docs()]
