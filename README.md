# 企业知识库智能助手（Enterprise Knowledge Agent）

基于 **RAG（检索增强生成）+ AI Agent** 技术的企业知识库智能问答系统。

系统统一管理企业文档与数据库中的知识，用户通过自然语言提问，智能体自动路由到最优回答路径——文档检索、SQL 查询或工具调用，实现「问一句，得答案」。

## 核心功能

### 多路径智能路由

系统根据问题内容自动选择最佳回答路径，无需手动切换：

| 路径 | 适用场景 | 工作原理 |
|------|---------|---------|
| **文档检索（RAG）** | 产品文档、技术方案、制度规范等非结构化知识 | 混合检索 → 上下文组装 → LLM 生成回答 |
| **数据库查询** | 员工信息、部门统计、人员花名册等结构化数据 | AI 生成 SQL → 执行查询 → AI 格式化结果 |
| **工具调用** | 当前时间、数学计算等通用能力 | 关键词匹配 / AI 自主决策 → 执行工具 → 格式化回答 |

### 混合检索引擎

文档检索采用 **双路召回 + RRF 融合重排** 策略，兼顾语义理解与精确匹配：

- **向量语义召回**：基于 BGE-Small-ZH 中文嵌入模型，捕捉语义相似性
- **关键词召回**：字符二元组覆盖率打分，对专有名词（人名、产品名、缩写）精准命中
- **RRF 融合**：Reciprocal Rank Fusion 算法融合两路排名，归一化后取 Top-K
- **统计类问题**：自动识别并切换为关键词全量召回，确保大模型看到完整数据

### 多轮对话记忆

- 基于 SessionId 的会话管理，支持追问上下文理解
- 滑动窗口保留最近 5 轮对话，自动截断过长回答防止上下文膨胀
- 跨路径上下文感知：上一轮走数据库查询，追问默认继续走数据库（文档类问题除外）

### 结构化数据查询

- 自动读取 MySQL 表结构，供 AI 生成 SQL 时参考
- 智能路由：包含「部门、员工、人数」等关键词自动走 SQL 路径
- 安全约束：仅允许 SELECT 查询，禁止写操作和危险关键字
- 查询失败自动回退到 RAG 路径

### 用户认证

- 基于 Token 的登录认证（内存态，重启后需重新登录）
- 可配置多用户账号，支持通过环境变量管理密码
- 认证功能可选开关，开发阶段可关闭

### 在线模型配置

- 页面内直接修改 API Base URL / Key / 模型名称，保存后立即生效
- 无需重启服务，支持在 Kimi、DeepSeek、OpenAI、Ollama 之间自由切换

## 技术架构

```
用户提问
   │
   ▼
┌──────────────────────────────────────────────────────────┐
│  智能路由（关键词匹配 + 会话上下文感知）                      │
│                                                          │
│  ┌─ 文档类问题 ──→ 混合检索 ──────────────────────────┐    │
│  │  ① 向量语义召回（BGE-Small-ZH 本地嵌入模型）        │    │
│  │  ② 关键词二元组召回（字符 Bigram 覆盖率打分）        │    │
│  │  ③ RRF 融合重排 + 归一化 Top-K                      │    │
│  │  ④ 组装上下文 + 多轮历史 + 严格提示词                │    │
│  │  ⑤ LLM 生成回答（附引用来源）                       │    │
│  └────────────────────────────────────────────────────┘    │
│                                                          │
│  ┌─ 数据类问题 ──→ 数据库查询 ────────────────────────┐    │
│  │  ① 读取表结构 → AI 生成 SELECT SQL                  │    │
│  │  ② 安全校验 → 执行查询 → AI 格式化结果               │    │
│  │  ③ 失败自动回退到 RAG 路径                           │    │
│  └────────────────────────────────────────────────────┘    │
│                                                          │
│  ┌─ 工具类问题 ──→ 工具调用 ──────────────────────────┐    │
│  │  ① 关键词直接匹配（时间、计算）                      │    │
│  │  ② AI Function Calling（RAG 未命中时自动触发）       │    │
│  │  ③ 执行工具 → AI 格式化结果                          │    │
│  └────────────────────────────────────────────────────┘    │
└──────────────────────────────────────────────────────────┘
   │
   ▼
返回答案（附引用来源 + 相关度得分）
```

### 技术栈

| 组件 | 选型 | 说明 |
|------|------|------|
| 语言 | Java 8 | Eclipse Temurin JDK 1.8 |
| Web 框架 | Spring Boot 2.7.18 | 内嵌 Tomcat，前后端一体 |
| AI 框架 | LangChain4j 0.35.0 | RAG 全流程 + Function Calling |
| 嵌入模型 | BGE-Small-ZH v1.5（本地 ONNX） | 中文优化嵌入模型，纯本地运行，无需 GPU |
| 向量存储 | InMemoryEmbeddingStore | 内存版，零部署成本，重启自动重建 |
| 大语言模型 | OpenAI 兼容接口 | 支持 Kimi / DeepSeek / OpenAI / 本地 Ollama |
| 文档解析 | Apache PDFBox + Apache POI | 支持 PDF / TXT / CSV / Word / Excel |
| 数据库 | MySQL 8.0 + Spring JDBC | 结构化数据查询（可选，未配置时自动降级） |
| 认证 | 自研 Token 认证（Filter + 内存态） | 可配置多用户，支持开关 |
| 前端 | 原生 HTML + CSS + JS | 单页应用，登录页 + 三视图布局 |

### 低内存设计

针对 16GB 内存的本地机器做了最低内存方案：

- 嵌入模型选用轻量版 BGE-Small-ZH（约 100MB），纯本地 ONNX 推理
- 向量库使用内存版，无独立数据库进程
- LLM 推理交给远端 API（或本地 Ollama），本地不承担大模型推理
- 整体服务运行内存约 **500MB**，不影响电脑日常使用

## 项目结构

```
enterprise-knowledge-agent/
├── docs/
│   ├── input/                              # 知识库文档目录（启动时自动向量化）
│   │   ├── 产品需求文档.txt                   # 示例文档 1
│   │   └── 技术架构文档.txt                   # 示例文档 2
│   └── sql/
│       └── init_employee.sql               # 示例数据库初始化脚本（员工花名册）
├── src/main/java/com/chenghao/study/knowledgeagent/
│   ├── KnowledgeAgentApplication.java      # 启动类
│   ├── config/
│   │   ├── LangChainConfig.java            # 嵌入模型 + 向量库 Bean 配置
│   │   ├── ChatModelProvider.java          # LLM 动态配置 + 限流重试
│   │   ├── AuthProperties.java             # 认证配置属性绑定
│   │   └── ApiAuthFilter.java              # API 认证过滤器
│   ├── controller/
│   │   ├── KnowledgeController.java        # 知识库 REST API（问答/上传/删除/设置）
│   │   └── AuthController.java             # 认证 REST API（登录/注销/当前用户）
│   ├── dto/
│   │   ├── ChatResult.java                 # 问答结果（答案 + 引用来源）
│   │   └── SearchHit.java                  # 检索命中（片段 + 归一化得分）
│   ├── service/
│   │   ├── DocumentService.java            # 文档加载、分片、向量化、索引重建
│   │   ├── SearchService.java              # 混合检索（向量 + 关键词 + RRF 融合）
│   │   ├── ChatService.java                # 核心：智能路由 + 多轮记忆 + 提示词工程
│   │   ├── DatabaseSchemaService.java      # 数据库表结构读取（供 AI 生成 SQL）
│   │   ├── DatabaseQueryService.java       # SQL 执行 + 安全校验 + 结果格式化
│   │   └── AuthService.java                # 登录校验 + Token 会话管理
│   └── tool/
│       └── AgentTools.java                 # 智能体工具集（日期查询、数学计算）
├── src/main/resources/
│   ├── application.yml                     # 配置文件（LLM/数据库/知识库/认证）
│   └── static/                             # 前端页面
│       ├── index.html                      # 主页面（登录 + 三视图）
│       ├── style.css                       # 样式
│       └── app.js                          # 前端逻辑
├── scripts/
│   └── inspect-xlsx.ps1                    # Excel 检查辅助脚本
└── pom.xml                                 # Maven 构建配置
```

## 快速开始

### 环境要求

- **JDK 8+**（推荐 Eclipse Temurin 1.8）
- **Maven 3.6+**
- **MySQL 8.0**（可选，用于结构化数据查询；未配置时自动降级，仅使用文档检索）
- **一个 OpenAI 兼容的 LLM 接口**（任选其一）：
  - Kimi（Moonshot）/ DeepSeek / OpenAI 等云端 API（需 API Key）
  - 本地 Ollama（免费，无需联网）

### 1. 克隆项目

```bash
git clone https://github.com/wbg812/enterprise-knowledge-agent.git
cd enterprise-knowledge-agent
```

### 2. 初始化数据库（可选）

如果需要使用结构化数据查询功能：

```bash
mysql -u root -p -e "CREATE DATABASE IF NOT EXISTS employee CHARACTER SET utf8mb4;"
mysql -u root -p employee < docs/sql/init_employee.sql
```

> 不配置数据库也可正常运行，系统会自动降级为纯文档检索模式。

### 3. 配置 LLM

编辑 `src/main/resources/application.yml`，或通过环境变量设置：

| 环境变量 | 说明 | 默认值 |
|---------|------|--------|
| `OPENAI_API_KEY` | LLM API 密钥（必填） | 无 |
| `OPENAI_BASE_URL` | API 基础地址 | `https://api.moonshot.cn/v1` |
| `OPENAI_MODEL` | 模型名称 | `moonshot-v1-32k` |
| `DB_PASSWORD` | MySQL 密码 | `123456` |
| `AUTH_PASSWORD_ADMIN` | admin 用户密码 | `admin123` |
| `AUTH_PASSWORD_ZHANGSAN` | zhangsan 用户密码 | `zs@2026` |

常用 LLM 配置示例：

```yaml
# Kimi（Moonshot）—— 默认配置，国内直连
langchain4j:
  openai:
    api-key: ${OPENAI_API_KEY:}
    base-url: https://api.moonshot.cn/v1
    model: moonshot-v1-32k
    temperature: 0.2

# DeepSeek
# base-url: https://api.deepseek.com/v1
# model: deepseek-chat

# 本地 Ollama（免费，无需联网）
# base-url: http://localhost:11434/v1
# model: qwen2.5
```

> 也可以启动后在页面「模型配置」中直接修改，保存后立即生效。

### 4. 启动

**方式一：IDEA 直接启动（推荐）**

打开 `KnowledgeAgentApplication.java`，点击 `main` 方法旁的 ▶ 运行即可。

**方式二：Maven 命令**

```powershell
mvn spring-boot:run
```

启动日志中出现「文档处理完成」即表示示例文档已向量化入库。

### 5. 使用

浏览器访问 **http://localhost:8080**

1. **登录**：使用配置的账号登录（默认 admin / admin123）
2. **智能问答**：输入问题，系统自动路由到最优回答路径
   - 文档类：「项目的技术架构是什么？」「RAG 的工作流程是怎样的？」
   - 数据类：「技术部有多少人？」「列出所有硕士学历的员工」
   - 工具类：「今天几号？」「1250 乘以 0.8 等于多少？」
3. **知识库管理**：上传 / 删除文档（支持 PDF、TXT、CSV、Word、Excel）
4. **模型配置**：在线切换 LLM 提供商，无需重启

## REST API

### 认证接口

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/login` | 登录，Body: `{"username": "admin", "password": "admin123"}` |
| POST | `/api/logout` | 注销（Header: `X-Auth-Token`） |
| GET | `/api/me` | 获取当前登录用户 |

### 知识库接口

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/chat` | 智能问答，Body: `{"message": "问题", "sessionId": "可选"}` |
| DELETE | `/api/chat/{sessionId}` | 清空会话记忆（开启新对话） |
| POST | `/api/upload` | 上传文档（multipart，参数名 `file`） |
| GET | `/api/documents` | 获取已入库文档列表 |
| DELETE | `/api/documents/{filename}` | 删除文档并重建向量索引 |
| POST | `/api/settings` | 更新 LLM 配置（立即生效），Body: `{"baseUrl", "apiKey", "model"}` |
| GET | `/api/status` | 系统状态（已处理文档数） |

> 除 `/api/login` 和静态页面外，所有接口需在 Header 中携带 `X-Auth-Token`。

## 工作机制详解

### 智能路由策略

系统通过关键词匹配 + 会话上下文双重判断，自动将问题分发到最优路径：

- **文档类关键词**（文档、方案、规范、架构、部署…）→ RAG 检索
- **数据类关键词**（部门、员工、人数、工号…）→ 数据库查询
- **工具类关键词**（今天、计算、等于多少…）→ 工具调用
- **上下文感知**：上一轮走数据库，追问默认继续（文档类问题除外）
- **自动降级**：数据库查询失败自动回退到 RAG；RAG 未找到文档时触发工具调用

### 文档管理

- **增量向量化**：上传新文档时只对新增文件向量化，不会重复处理旧文档
- **删除清理**：删除文档时清空向量库并重建索引，不留残留向量
- **多格式支持**：PDF / TXT / CSV 使用默认解析器，Word / Excel 使用 Apache POI
- **文档分片**：按配置的 `chunk-size` 分片、`chunk-overlap` 重叠（默认 800/100）
- **内存存储**：向量库为内存版，重启后自动从 `docs/input` 重新向量化

### 提示词工程

- **严格约束**：只允许基于文档回答，禁止自行推断
- **统计类增强**：涉及数量统计时，要求先逐一列出条目再给总数，减少 LLM 计数错误
- **历史截断**：历史回答超过 500 字符自动截断，防止上下文膨胀
- **限流重试**：遇到 API 限流（RPM）自动等待 20 秒后重试，最多 5 次

## 迭代计划

- [ ] 向量库替换为持久化方案（如 Milvus / PgVector），支持海量文档
- [ ] 部署到云服务器，支持多人访问
- [ ] 流式输出（SSE）提升对话体验
- [ ] 对话历史持久化与导出
- [ ] 权限管理（按部门隔离知识库）
- [ ] 接入更多工具（天气、翻译、企业内部 API 等）
- [ ] 支持语音输入

## License

MIT
