# Third-Party Licenses — DocQA

本仓库自身采用 Apache License 2.0（见 LICENSE）；见根目录 `NOTICE` 与 `LICENSE`。

Apache-2.0 第 4 节要求保留第三方归属与许可声明，本文件是该声明的汇总入口。
下表由 `ops/gen-third-party-licenses.py` 从本机
`.venv/Lib/site-packages/*.dist-info/METADATA` 实际读取汇总
（`License-Expression` / License Classifier / `License` 字段），按许可证类别分组。
**权威且带版本与哈希的清单**是 `.github/workflows/sbom.yml` 产出的可下载 SBOM artifact；
若此处与发行包内 `LICENSE` / `METADATA` 不一致，以发行包内文本为准。
本文件不构成法律意见。

共识别 117 个第三方发行包，18 类许可证声明。

## 3-Clause BSD License（1）

| 组件 | 版本 |
| --- | --- |
| `protobuf` | `7.36.2` |

## Apache Software License（10）

| 组件 | 版本 |
| --- | --- |
| `cyclonedx-python-lib` | `11.12.0` |
| `distro` | `1.9.0` |
| `minio` | `7.2.20` |
| `pip-api` | `0.0.35` |
| `pip-audit` | `2.10.1` |
| `py-serializable` | `2.2.0` |
| `requests` | `2.34.2` |
| `requests-toolbelt` | `1.0.0` |
| `sniffio` | `1.3.1` |
| `sortedcontainers` | `2.4.0` |

## Apache-2.0（20）

| 组件 | 版本 |
| --- | --- |
| `cachecontrol` | `0.14.4` |
| `googleapis-common-protos` | `1.76.0` |
| `license-expression` | `30.4.4` |
| `msgpack` | `1.2.4` |
| `opentelemetry-api` | `1.45.2` |
| `opentelemetry-exporter-http-transport` | `0.66b2` |
| `opentelemetry-exporter-otlp-common` | `0.66b2` |
| `opentelemetry-exporter-otlp-proto-common` | `1.45.2` |
| `opentelemetry-exporter-otlp-proto-http` | `1.45.2` |
| `opentelemetry-instrumentation` | `0.66b2` |
| `opentelemetry-instrumentation-asgi` | `0.66b2` |
| `opentelemetry-instrumentation-fastapi` | `0.66b2` |
| `opentelemetry-proto` | `1.45.2` |
| `opentelemetry-sdk` | `1.45.2` |
| `opentelemetry-semantic-conventions` | `0.66b2` |
| `opentelemetry-util-http` | `0.66b2` |
| `pytest-asyncio` | `1.4.1.post1` |
| `python-multipart` | `0.0.33` |
| `tenacity` | `9.2.1` |
| `tzdata` | `2026.6` |

## Apache-2.0 AND BSD-2-Clause（1）

| 组件 | 版本 |
| --- | --- |
| `prometheus-client` | `0.26.0` |

## Apache-2.0 OR BSD-3-Clause（1）

| 组件 | 版本 |
| --- | --- |
| `cryptography` | `50.0.2` |

## Apache-2.0 OR MIT（1）

| 组件 | 版本 |
| --- | --- |
| `ormsgpack` | `1.13.0` |

## BSD License（5）

| 组件 | 版本 |
| --- | --- |
| `colorama` | `0.4.6` |
| `httpx` | `0.28.1` |
| `jsonpointer` | `3.10` |
| `packaging` | `24.2` |
| `respx` | `0.23.1` |

## BSD-2-Clause（4）

| 组件 | 版本 |
| --- | --- |
| `boolean-py` | `5.0` |
| `pygments` | `2.21.0` |
| `wrapt` | `2.5.2` |
| `xxhash` | `4.0.2` |

## BSD-2-Clause AND Unlicense AND MIT（1）

| 组件 | 版本 |
| --- | --- |
| `pycryptodome` | `4.4.0` |

## BSD-3-Clause（18）

| 组件 | 版本 |
| --- | --- |
| `asgiref` | `3.13.0` |
| `click` | `8.5.1` |
| `fakeredis` | `2.40.0` |
| `httpcore` | `1.0.9` |
| `httpcore2` | `2.16.0` |
| `httpx2` | `2.16.0` |
| `idna` | `3.20` |
| `jsonpatch` | `1.44` |
| `lxml` | `6.1.3` |
| `pycparser` | `3.12` |
| `pypdf` | `7.0.0` |
| `python-dotenv` | `1.2.4` |
| `sse-starlette` | `3.7.0` |
| `starlette` | `1.10.0` |
| `uuid-utils` | `0.19.0` |
| `uvicorn` | `0.54.0` |
| `websockets` | `17.4` |
| `zstandard` | `0.25.0` |

## LGPL-3.0-only（2）

| 组件 | 版本 |
| --- | --- |
| `psycopg` | `3.3.8` |
| `psycopg-binary` | `3.3.8` |

## MIT（33）

| 组件 | 版本 |
| --- | --- |
| `annotated-doc` | `0.0.5` |
| `annotated-types` | `0.8.0` |
| `anyio` | `4.16.0` |
| `argon2-cffi` | `25.1.0` |
| `argon2-cffi-bindings` | `26.1.0` |
| `attrs` | `26.1.0` |
| `charset-normalizer` | `3.5.2` |
| `fastapi` | `0.162.1` |
| `filelock` | `4.2.9` |
| `iniconfig` | `2.3.1` |
| `jsonschema` | `4.26.0` |
| `jsonschema-specifications` | `2025.9.1` |
| `langgraph` | `1.2.20` |
| `langgraph-checkpoint` | `4.3.1` |
| `langgraph-prebuilt` | `1.1.1` |
| `langgraph-sdk` | `0.4.14` |
| `pip` | `26.2.1` |
| `pip-requirements-parser` | `32.0.1` |
| `platformdirs` | `4.14.5` |
| `pydantic` | `2.14.3` |
| `pydantic-core` | `2.50.4` |
| `pydantic-settings` | `2.16.2` |
| `pyjwt` | `2.15.1` |
| `pyparsing` | `3.3.3` |
| `pytest` | `9.2.0` |
| `redis` | `8.1.0` |
| `referencing` | `0.37.0` |
| `rpds-py` | `2026.9.1` |
| `ruff` | `0.17.4` |
| `tomli` | `2.5.0` |
| `truststore` | `0.10.5` |
| `typing-inspection` | `0.4.5` |
| `urllib3` | `2.8.0` |

## MIT License（14）

| 组件 | 版本 |
| --- | --- |
| `h11` | `0.16.0` |
| `langchain-core` | `1.7.6` |
| `langchain-protocol` | `0.0.21` |
| `langsmith` | `0.14.17` |
| `markdown-it-py` | `4.2.0` |
| `mcp` | `2.3.0` |
| `mcp-types` | `2.3.0` |
| `mdurl` | `0.1.2` |
| `packageurl-python` | `0.17.6` |
| `pluggy` | `1.7.0` |
| `python-docx` | `1.2.0` |
| `pyyaml` | `6.0.3` |
| `rich` | `15.0.0` |
| `tomli-w` | `1.2.0` |

## MIT-0（1）

| 组件 | 版本 |
| --- | --- |
| `cffi` | `2.1.1` |

## Mozilla Public License 2.0 (MPL 2.0)（1）

| 组件 | 版本 |
| --- | --- |
| `certifi` | `2026.10.23` |

## MPL-2.0 AND (Apache-2.0 OR MIT)（1）

| 组件 | 版本 |
| --- | --- |
| `orjson` | `3.13.0` |

## PSF-2.0（1）

| 组件 | 版本 |
| --- | --- |
| `typing-extensions` | `4.16.0` |

## Python Software Foundation License（2）

| 组件 | 版本 |
| --- | --- |
| `defusedxml` | `0.7.1` |
| `pywin32` | `312` |
