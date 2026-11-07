# 命名与改名约定

仓库目录名、GitHub 仓库名、代码里各种 `docqa` 字符串的关系经常被混为一谈。
本文给出明确边界：**改名只影响路径，不影响标识符**。

## 1. 随目录名变化的部分（改名时必须同步）

| 位置 | 说明 | 处理方式 |
|---|---|---|
| 本地绝对路径 | IDE 工程文件、本机快捷方式、手工命令里的 `C:\...\<旧目录>` | 由脚本按自身位置推导，不写死 |
| WSL 挂载路径 | `/mnt/c/.../<旧目录>` | 同上 |
| Python 可编辑安装 | venv 的 `_editable_impl_*.pth` 写死安装时的绝对路径 | 目录改名后需在 WSL 重跑 `pip install -e <新路径>/ai-python[mq] --no-deps` |
| 文档里的目录树根 | `README.md` 的 `DocQA/` | 手工同步 |
| 运行期日志/取证文件 | `.artifacts/` 下历史日志与探针脚本 | 均已加入 `.gitignore`，不入仓库；探针脚本改为相对定位 |

**根治办法**：所有启动脚本一律从 `$PSScriptRoot` / `${BASH_SOURCE[0]}` 上溯推导仓库根，
不接受、也不需要用户传入仓库路径（`prepare-local-stack.sh`、`setup-parse-worker.sh install`
的路径参数已改为可选，省略即自动推导）。这样目录再改名也不会牵连任何东西。

## 2. 不随目录名变化的部分（改动即为破坏性变更）

以下都是**配置契约或数据契约**，与文件夹叫什么无关，重命名会造成线上/本机全线故障：

| 标识符 | 用途 | 改动的后果 |
|---|---|---|
| `com.docqa.*` | Java 包名 | 全模块 import、ArchUnit 规则、Spring 扫描路径全部失效 |
| `docqa_ai` | Python 包名 / 模块入口 | `python -m docqa_ai.*`、venv 入口脚本、测试导入全断 |
| `docqa` | Maven 父工程 `artifactId` | 所有子模块 `<parent>` 解析失败 |
| `docqa.*` / `DOCQA_*` | Spring Boot 配置前缀与环境变量命名空间 | 宽松绑定失效，JWT、DB、MQ、MinIO 配置全部回退默认值 |
| `docqa-documents` | MinIO 桶名 | 已有对象与预签名 URL 全部 404 |
| `docqa-user-service` / `docqa-api` | JWT issuer / audience | 已签发 token 全部校验失败 |
| `https://docqa.example/problems/*` | ProblemDetail `type` URI | 契约测试与前端错误分类失配 |
| `docqa` / `docqa_vec` | MySQL / PostgreSQL 库名 | 数据迁移级别变更 |
| `docqa-*` | Docker 容器与卷名 | 卷数据丢失、启动脚本找不到容器 |

> 这些名称属于**内部技术命名空间**，不是产品对外名称，也不是任何 AI 工具的痕迹。
> 对外产品名统一用 `DocQA`（README、GitHub 仓库名、文档标题）。

## 3. Git / GitHub 与目录名的关系

- Git 不存储工作区的绝对路径：`.git/config` 只有 `remote.origin.url`、分支与用户信息，
  目录改名后无需任何 Git 操作。
- 远程地址由 **GitHub 仓库名**决定，与本地目录名无关：
  `https://github.com/ybw0405-study/DocQA.git`。
- CI 使用 `working-directory: backend-java` 这类相对路径，同样不受影响。
- 唯一需要留意的是 IDE：`.idea/modules.xml` 里的 `.iml` 文件名若沿用旧名，
  改目录后建议一并改为 `DocQA.iml`（该文件已被 `.gitignore` 忽略，不进仓库）。

## 4. 真正会在重启后失效的是 IP，不是名字

WSL2 默认 NAT 网络下 `eth0` 的 `172.16.0.0/12` 地址每次 Windows 重启都会变。
Windows 侧 `DOCQA_*` 里凡是写了这个 IP 的（MinIO endpoint、PG DSN、
RocketMQ namesrv）都会失效；broker 的 `brokerIP1` 也会指向旧地址。

处理方式是 [start-local.ps1](start-local.ps1) + [install-wsl-autostart.sh](install-wsl-autostart.sh)
组成的两段式自启：WSL 侧每次启动重渲染配置，Windows 侧登录后读取新 IP 并回写环境变量。
详见 [getting-started.md](getting-started.md) 的「开机自启」一节。
