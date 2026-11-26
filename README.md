# Novel Agent — 小说智能续写系统

上传小说 → 语义分块 → 向量检索 → AI 续写，支持联网搜索。

---

## 快速开始（Docker All-in-One）

一个镜像包含所有服务：Spring Boot + PostgreSQL + Redis + SearXNG。只需要 Docker。

### 首次启动

```bash
# 1. 启动容器（后台运行）
docker run -d --name novel-agent \
  -p 8080:8080 \
  -v novel-agent-data:/data \
  novel-agent:latest

# 2. 配置 API Key
docker exec -it novel-agent onboard

# 3. 浏览器打开 http://localhost:8080
```

`onboard` 按交互式引导选择 LLM / Embedding 厂商，填入 API Key。配置保存到 `/data/config.env`，重启不会丢失。

### 日常操作

```bash
docker start novel-agent     # 启动
docker stop novel-agent      # 停止
docker logs -f novel-agent   # 查看日志
docker exec -it novel-agent onboard  # 修改配置
```

### 清除数据

```bash
docker stop novel-agent && docker rm novel-agent
docker volume rm novel-agent-data
```

---

## 构建镜像

### 从源码构建

```bash
git clone https://github.com/G33s3Fr13s/novel-agent.git
cd novel-agent

# 先下载 SearXNG 源码 zip（一次性的，6.3MB）
# 浏览器打开 https://github.com/searxng/searxng/archive/refs/heads/master.zip
# 保存为 docker/searxng-master.zip

# 构建（首次 ~5 分钟，后续有缓存 ~30 秒）
docker build -t novel-agent:latest -f docker/Dockerfile .
```

> **为什么预下载 zip？** Docker 构建时从 GitHub 下载 SearXNG 源码在国内可能超时。提前把 zip 放到 `docker/` 目录下，构建时直接 COPY 进镜像，不依赖网络。

### 镜像部署到服务器

**方案 A：导出镜像文件**（不需要 GitHub / Docker Hub）

```bash
# 本地导出
docker save novel-agent:latest -o novel-agent.tar

# 传到服务器后加载
docker load -i novel-agent.tar
docker run -d --name novel-agent -p 8080:8080 -v novel-agent-data:/data novel-agent:latest
```

**方案 B：推送到 Docker Hub，服务器拉取**

```bash
# 本地
docker tag novel-agent:latest 你的用户名/novel-agent:v1
docker push 你的用户名/novel-agent:v1

# 服务器
docker run -d --name novel-agent -p 8080:8080 -v novel-agent-data:/data 你的用户名/novel-agent:v1
```

---

## 容器启动流程

`entrypoint.sh` 自动完成以下步骤：

1. 生成 JWT secret（首次）→ 存 `/data/.jwt_secret`
2. PostgreSQL initdb + 建库建表 + pgvector 扩展（首次）
3. 启动 PostgreSQL（127.0.0.1:5432）
4. 启动 Redis（127.0.0.1:6379）
5. 启动 SearXNG（127.0.0.1:8088）
6. 加载 `/data/config.env`（API Key）
7. 启动 Spring Boot（0.0.0.0:8080）

| 数据 | 容器内路径 | 说明 |
|------|-----------|------|
| 配置 | `/data/config.env` | onboard 写入 |
| JWT 密钥 | `/data/.jwt_secret` | 首次自动生成 |
| PostgreSQL | `/data/postgres/` | 数据库文件 |
| 应用日志 | `/data/app.log` | Spring Boot 日志 |
| SearXNG 日志 | `/data/searxng.log` | 搜索引擎日志 |

---

## 开发模式（前后端分离 + 热重载）

```bash
cp .env.example .env   # 填入 API Key
docker compose run --rm onboard
docker compose up -d
```

| 服务 | 地址 |
|------|------|
| 前端（Vite HMR） | http://localhost:5173 |
| 后端 API | http://localhost:8080 |
| PostgreSQL | localhost:15432 |
| SearXNG | http://localhost:8088 |

---

## 手动安装（不用 Docker）

### 环境

| 组件 | 版本 |
|------|------|
| Java JDK | 21+ |
| Maven | 3.9+ |
| Node.js | 20+ |
| PostgreSQL 16 + pgvector | 16 |

### 步骤

**1. 数据库**

```sql
CREATE USER novel WITH PASSWORD 'novel123';
CREATE DATABASE novel_agent OWNER novel;
\c novel_agent
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS pgcrypto;
```

导入迁移：

```bash
for f in sql/V*.sql; do
  psql -U novel -d novel_agent -f "$f"
done
```

**2. 配置 `.env`**

```
LLM_API_KEY=sk-你的Key
LLM_BASE_URL=https://api.deepseek.com
LLM_MODEL=deepseek-chat
LLM_EMBED_API_KEY=sk-你的Key
LLM_EMBED_BASE_URL=https://dashscope.aliyuncs.com/compatible-mode/v1
LLM_EMBED_MODEL=text-embedding-v4
JWT_SECRET=随机字符串不少于32字符
```

**3. 构建**

```bash
cd frontend && npm install && npm run build && cd ..
mvn package -DskipTests
java -jar target/novel-agent-0.1.0.jar
```

---

## 本地推理（Ollama）— 版权隐私方案

对于注重版权的作者，可以让 LLM / Embedding 走**本地 Ollama**，正文不经任何第三方 AI 公司。

### 隐私分级

| 层级 | 含义 | 是否解决 |
|------|------|---------|
| 第一层 | 正文不发送给外部 AI 公司（DeepSeek / 阿里 / OpenAI） | ✅ Ollama 解决 |
| 第二层 | 正文不落在任何云服务器（连数据库也要放本地） | ❌ 需作者自建整机 |

本项目支持第一层：正文仍存在你的云服务器 PostgreSQL 里（RAG 检索的本质），但**推理和向量化都在作者本机完成**。

### 接入方式（零代码改动）

应用走 **OpenAI 兼容协议**，Ollama 正好暴露同样的接口，直接对接：

1. 作者电脑安装 Ollama，拉取模型（无需我们参与）：
   ```bash
   ollama pull qwen2.5:14b      # 对话 / 续写
   ollama pull bge-m3           # 向量化（1024 维）
   ```

2. 运行 `docker exec -it novel-agent onboard`，两处都选 **Custom**：
   ```
   LLM:      Base URL = http://<作者机器>:11434/v1
             Model    = qwen2.5:14b
             API Key  = 随便填（Ollama 不校验）

   Embedding: Base URL = http://<作者机器>:11434/v1
             Model    = bge-m3
             API Key  = 随便填
   ```

> **维度要求**：`sql/V1__init.sql` 里向量列写死 `vector(1024)`，因此 embedding 模型必须选 **1024 维**。`bge-m3` 正好是 1024 维；`nomic-embed-text`（768 维）不匹配，会报错。

### 连接拓扑：云服务器 → 作者本机

作者家 Ollama 在 NAT 后面，云服务器够不着，需要一条隧道。推荐 **Tailscale**（免费、免公网 IP、NAT 穿透）：

```
作者电脑 (Tailscale + Ollama :11434)
     │  虚拟内网 100.x.x.x
云服务器 (Tailscale + 应用)
     │  base_url = http://100.x.x.x:11434/v1
     │
     └─ 聊天/续写 → 回作者家推理 → 原文返回，全程不出作者局域网
```

备选：内网穿透（frp）、路由器端口映射 + DDNS。

---

## 功能特性

- **语义分块**：相邻段落向量相似度 → 检测跳变点 → 自适应切分
- **双路检索**：BiEncoder（向量）+ GraphRAG（知识图谱）→ RRF 融合
- **联网搜索**：SearXNG 聚合百度 / 360 → 聊天和续写均可开启
- **站点过滤**：指定起点 / 番茄 / 七猫等站点，或全网页搜索
- **智能续写**：RAG 检索 + 联网搜索 → LLM 续写 → 自动入库 + 增量图谱
- **剧情图谱**：实体识别 + 关系提取 → 增量更新，可视化故事线
- **双 Token 认证**：access token（15min，内存持有）+ refresh token（7d，httpOnly Cookie）
- **RAG 评测**：Precision / Recall / GraphRAG 增益，支持黄金测试集
- **Web 引导**：首次启动自动跳转配置页，可视化填入 API Key

---

## 架构

```
浏览器 → :8080 → Spring Boot（前端静态文件 + REST API）
                      │
                      ├── PostgreSQL 16 + pgvector  （章节块向量 + 图谱 + 业务数据）
                      ├── Redis                     （续写任务状态）
                      ├── SearXNG (:8088) → 百度/360 （联网搜索）
                      ├── LLM API                   （聊天 / 续写 / 实体提取）
                      └── Embedding API             （文本向量化）
```

---

## License

MIT
