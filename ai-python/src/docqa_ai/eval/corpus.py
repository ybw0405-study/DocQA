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


@dataclass(frozen=True)
class NegativeQuery:
    """「文档无答案」负例：relevant 为空，正确行为是不召回任何块。

    跨文档评测里它测的是「系统会不会把干扰块错当成答案」——这是单文档 scope 下
    永远测不到的 RAG 核心难点。正例用 Recall/MRR/nDCG，负例用误召回率单独报告。
    """

    query_id: str
    question: str


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
                queries=(Query(query_id=f"{qid}-q1", question=content.split("，")[0] + "？", relevant=(0,)),),
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
    (
        4,
        "《中华人民共和国劳动合同法》第八十七条规定，用人单位违反本法规定解除或者终止劳动合同的，"
        "应当依照本法第四十七条规定的经济补偿标准的二倍向劳动者支付赔偿金。",
    ),
    (
        5,
        "《中华人民共和国道路交通安全法》第九十一条第二款规定，醉酒驾驶机动车的，由公安机关交通"
        "管理部门约束至酒醒，吊销机动车驾驶证，依法追究刑事责任；五年内不得重新取得机动车驾驶证。",
    ),
    (
        6,
        "《中华人民共和国民法典》第一千零八十九条规定，离婚时，夫妻共同债务应当共同偿还。共同财产"
        "不足清偿或者财产归各自所有的，由双方协议清偿；协议不成的，由人民法院判决。",
    ),
    (
        7,
        "《中华人民共和国个人所得税法》第六条规定，居民个人的综合所得，以每一纳税年度的收入额减除"
        "费用六万元以及专项扣除、专项附加扣除和依法确定的其他扣除后的余额，为应纳税所得额。",
    ),
)

_REAL_QUERIES: tuple[Query, ...] = (
    Query("real-q1", "工作满六个月不满一年，经济补偿怎么算？", (0,)),
    Query("real-q2", "哪些债务属于夫妻共同债务？", (1,)),
    Query("real-q3", "个人所得税综合所得的税率是多少？", (2,)),
    Query("real-q4", "饮酒驾驶机动车怎么处罚？", (3,)),
    Query("real-q5", "公司违法解除劳动合同应该赔几倍补偿？", (4,)),
    Query("real-q6", "醉酒驾驶机动车会被怎么处罚？", (5,)),
    Query("real-q7", "离婚时夫妻共同债务怎么偿还？", (6,)),
    Query("real-q8", "综合所得每年的基本减除费用是多少？", (7,)),
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


# ---------------------------------------------------------------------------
# 跨文档 hard-negative 干扰文档（无查询，只作为候选池干扰）
# ---------------------------------------------------------------------------
#
# 与 real-legal 的「经济补偿/赔偿金」「饮酒/醉酒驾驶」同主题但表述不同，
# 专门压跨文档召回：查询在某个文档内，干扰块的词面与答案高度重叠，
# 单文档 scope 下永远撞不到它们，跨文档才暴露排序/召回是否被稀释。

_DISTRACTOR_DOCS: tuple[CorpusDocument, ...] = (
    CorpusDocument(
        document_id="distractor-labor",
        title="劳动合同实务问答",
        chunks=(
            (
                0,
                "劳动者主动辞职的，用人单位无需支付经济补偿金，但用人单位拖欠工资或者未依法缴纳社会保险费的除外。",
            ),
            (
                1,
                "试用期工资不得低于本单位相同岗位最低档工资或者劳动合同约定工资的百分之八十，"
                "并不得低于当地最低工资标准。",
            ),
            (
                2,
                "用人单位单方解除劳动合同，应当事先将理由通知工会；违反法律规定的，应当向劳动者支付赔偿金。",
            ),
        ),
    ),
    CorpusDocument(
        document_id="distractor-traffic",
        title="交通事故处理指南",
        chunks=(
            (
                0,
                "发生交通事故后当事人应当保护现场，抢救受伤人员，并立即报告执勤的交通警察或者公安机关交通管理部门。",
            ),
            (
                1,
                "机动车之间发生交通事故的，由有过错的一方承担赔偿责任；双方都有过错的，按照各自过错的比例分担责任。",
            ),
            (
                2,
                "机动车与非机动车驾驶人、行人之间发生交通事故，非机动车驾驶人、行人没有过错的，"
                "由机动车一方承担赔偿责任。",
            ),
        ),
    ),
)


def _distractor_docs() -> list[CorpusDocument]:
    return list(_DISTRACTOR_DOCS)


# ---------------------------------------------------------------------------
# 「文档无答案」负例查询
# ---------------------------------------------------------------------------

_NEGATIVE_QUERIES: tuple[NegativeQuery, ...] = (
    NegativeQuery("neg-q1", "工伤认定的具体标准是什么？"),
    NegativeQuery("neg-q2", "如何申请发明专利？"),
    NegativeQuery("neg-q3", "住房公积金贷款额度怎么计算？"),
)


def load_negative_queries() -> tuple[NegativeQuery, ...]:
    """返回全部负例查询：语料里没有答案，期望系统不召回任何块。"""
    return _NEGATIVE_QUERIES


def load_corpus() -> list[CorpusDocument]:
    """返回全部评测文档（合成 + 真实混合 + 跨文档干扰）。"""
    return [*_synthetic_docs(), _labor_doc(), *_real_docs(), *_distractor_docs()]
