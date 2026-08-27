# 企业知识库智能助手（Enterprise Knowledge Agent）

基于 **RAG（检索增强生成）** 技术的企业知识库问答系统最小可运行 Demo。

## 解决什么问题

企业普遍面临以下知识管理痛点：

- 📄 **文档太多**：产品文档、技术文档、制度规范散落各处，无法统一整理
- 🔍 **找不到信息**：想查某个问题时，不知道答案在哪份文档里
- 🧠 **难以分析**：文档内容需要人工阅读、比对、总结，效率低下

本系统将所有文档统一向量化入库，用户直接用自然语言提问，智能体自动检索相关文档片段并由大模型生成回答，实现「问一句，得答案」。

## 技术架构

```
用户提问
   │
   ▼
┌─────────────────────────────────────────────┐
│  ① 问题向量化（AllMiniLmL6V2 本地嵌入模型）     │
│  ② 向量相似度检索（InMemory 向量库，Top-3 片段） │
│  ③ 组装上下文 + 提示词                         │
│  ④ LLM 生成回答（OpenAI API / 本地 Ollama）    │
└─────────────────────────────────────────────┘
   │
   ▼
返回答案（附引用来源片段）
```

### 技术栈

| 组件 | 选型 | 说明 |
|------|------|------|
| 语言 | Java 8 | Eclipse Temurin JDK 1.8 |
| Web 框架 | Spring Boot 2.7.18 | 内嵌 Tomcat，前后端一体 |
| AI 框架 | LangChain4j 0.35.0 | RAG 全流程 |
| 嵌入模型 | AllMiniLmL6V2（本地 ONNX） | 384 维向量，**纯本地运行，无需 GPU、无需联网**，内存占用约 80MB |
| 向量存储 | InMemoryEmbeddingStore | 内存版，零部署成本（后续可平滑迁移到云端向量库） |
| 大语言模型 | OpenAI 兼容接口 | 支持 OpenAI API、DeepSeek、本地 Ollama 等 |
| 文档解析 | Apache PDFBox | 支持 PDF / TXT |
| 前端 | 原生 HTML + CSS + JS | 单页应用，随 jar 一起分发 |

### 低内存设计

针对 16GB 内存的本地机器做了最低内存方案：

- 嵌入模型选用轻量版 AllMiniLmL6V2（约 80MB），而非大尺寸模型
- 向量库使用内存版，无独立数据库进程
- LLM 推理交给远端 API（或本地 Ollama），本地不承担大模型推理
- 整体服务运行内存约 **500MB**，不影响电脑日常使用

## 项目结构

```
enterprise-knowledge-agent/
├── docs/input/                        # 知识库文档目录（启动时自动向量化）
│   ├── 产品需求文档.txt                 # 示例文档 1
│   └── 技术架构文档.txt                 # 示例文档 2
├── src/main/java/com/chenghao/study/knowledgeagent/
│   ├── KnowledgeAgentApplication.java # 启动类
│   ├── config/
│   │   └── LangChainConfig.java       # 嵌入模型、向量库、LLM 配置
│   ├── service/
│   │   ├── DocumentService.java       # 文档加载、分片、向量化、索引重建
│   │   └── ChatService.java           # RAG 核心：检索 + 生成
│   └── controller/
│       └── KnowledgeController.java   # REST API
├── src/main/resources/
│   ├── application.yml                # 配置文件
│   └── static/                        # 前端页面
│       ├── index.html
│       ├── style.css
│       └── app.js
└── pom.xml
```

## 快速开始

### 环境要求

- JDK 8+
- Maven 3.6+
- 一个 OpenAI 兼容的 LLM 接口（任选其一）：
  - OpenAI / DeepSeek / Moonshot 等云端 API（需 API Key）
  - 本地 Ollama（免费，无需联网）

### 1. 配置 LLM

编辑 `src/main/resources/application.yml`：

```yaml
langchain4j:
  openai:
    api-key: sk-你的密钥
    base-url: https://api.openai.com/v1    # Ollama 填: http://localhost:11434/v1
    model: gpt-3.5-turbo                   # Ollama 填: qwen2.5 / llama3 等
```

> ⚠️ 不要把真实 API Key 提交到 git 仓库，建议改用环境变量：`api-key: ${OPENAI_API_KEY:}`

### 2. 启动

**方式一：IDEA 直接启动（推荐）**

打开 `KnowledgeAgentApplication.java`，点击 `main` 方法旁的 ▶️ 运行即可。

**方式二：Maven 命令**

```powershell
mvn spring-boot:run
```

启动日志中出现「文档处理完成」即表示两个示例文档已向量化入库。

### 3. 使用

浏览器访问 **http://localhost:8080**

- 💬 **智能问答**：输入问题，如「这个项目的技术架构是什么？」「系统对内存有什么要求？」
- 📄 **文档管理**：上传新的 PDF / TXT 文档（自动向量化），或删除文档（自动清理向量）
- ⚙️ **设置**：API 配置说明

## REST API

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/chat` | 知识库问答，Body: `{"message": "问题"}` |
| POST | `/api/upload` | 上传文档（multipart，参数名 `file`） |
| GET | `/api/documents` | 获取已入库文档列表 |
| DELETE | `/api/documents/{filename}` | 删除文档并重建向量索引 |
| GET | `/api/status` | 系统状态（已处理文档数） |

## 工作机制说明

- **增量向量化**：上传新文档时只对新增文件向量化，不会重复处理旧文档
- **删除清理**：删除文档时会清空向量库并重建索引，不留残留向量
- **文档分片**：按 200 字符分片、50 字符重叠（可在 `application.yml` 中调整 `chunk-size` / `chunk-overlap`），保证检索精度
- **内存存储**：向量库为内存版，**重启后会自动从 `docs/input` 重新向量化**，数据不丢失

## 迭代计划

- [ ] 向量库替换为持久化方案（如 Milvus / PgVector），支持海量文档
- [ ] 部署到云服务器，支持多人访问
- [ ] 支持更多文档格式（Word、Excel、Markdown）
- [ ] 回答附带来源引用标注
- [ ] 流式输出（SSE）提升对话体验
- [ ] 对话历史记忆
- [ ] 权限管理（按部门隔离知识库）
