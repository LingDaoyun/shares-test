# shares-test

AI 股票研究与交易复盘系统，面向 A 股的长线价值研究、政策主题跟踪、短线右侧预选、特别关注、规则配置和交易复盘。系统只提供研究辅助和过程记录，不构成投资建议。

## 系统能力

```text
全 A 股数据 -> 行情/K线/财报/公告/政策 -> 规则与证据评分 -> 候选列表 -> 复盘与策略反馈
```

主要模块：

- `短线右侧`：全市场扫描右侧启动候选，结合 K 线、量能、筹码结构、资金流、财报质量和风险门禁。
- `长期价投`：按推荐指数展示长线样本，支持公司详情、推荐理由、风险观察和证据追踪。
- `政策解读`：每天定时拉取政策源，形成可追溯的政策主题和产业影响分析。
- `公司样本`：查看公司样本列表、公司详情、公告/财报/估值/证据缺口。
- `特别关注`：用户主动关注股票后的持续跟踪与分析历史。
- `交易复盘`：把推荐加入复盘，记录买卖成交、收益结果和策略反馈。
- `规则目录`：查看和维护可配置规则。
- `系统配置`：在现有数据库中动态维护大模型与政策源配置，保存后对下一次业务调用生效。

## 技术栈

```text
前端：React 18 + TypeScript + Vite + Tailwind
后端：Java 17 + Spring Boot 3
运行时配置：H2/PostgreSQL 数据库
存储：Docker 默认 H2 文件库，可切换 PostgreSQL/pgvector
容器：Docker Compose
AI：OpenAI 兼容接口，当前推荐 DeepSeek 配置
```

## 目录结构

```text
apps/api          Spring Boot 后端
apps/web-react    React 前端
infra/db/init     PostgreSQL 初始化脚本
docs              设计文档和补充说明
scripts           本地启动和运维脚本
docker-compose.yml
```

## 快速启动

### 1. 准备环境

需要本机已有：

```text
Java 17+
Maven 3.9+
Node.js 20+
Docker Desktop
```

首次前端安装依赖：

```bash
cd apps/web-react
npm install
```

### 2. 启动可选基础设施

默认 Docker 部署使用持久化 H2 文件库，不需要额外配置服务。需要 PostgreSQL、Redis 或 MinIO 时，可按需启动：

```bash
docker compose --profile infra up -d postgres redis minio
```

### 3. 配置模型密钥

复制 `.env.example` 为 `.env.local`，按实际 Provider 设置对应环境变量。不要把真实 API Key 写入 Git。模型名、Base URL 和政策源在系统配置页维护并写入数据库；API Key 也支持数据库保存，但部署环境优先使用环境变量。

### 4. 启动后端开发服务

```bash
./scripts/run-api-local.sh
```

后端默认地址：

```text
http://127.0.0.1:19080
```

健康检查：

```bash
curl http://127.0.0.1:19080/actuator/health
```

### 5. 启动前端开发服务

```bash
cd apps/web-react
npm run dev
```

前端默认地址：

```text
http://127.0.0.1:5176
```

## Docker 部署

React 容器直接复制 `apps/web-react/dist`，所以部署前必须先构建前端产物，否则页面会继续服务旧 JS 包。

推荐部署顺序：

```bash
cd apps/web-react
npm run build

cd ../..
mvn -pl apps/api -DskipTests package
docker compose --env-file .env.local up -d --build api web
```

查看容器状态：

```bash
docker compose ps
```

默认容器地址：

```text
ai-stock-api -> http://127.0.0.1:19080
ai-stock-web -> http://127.0.0.1:5176
```

如果页面仍显示旧值，先强刷浏览器：

```text
macOS: Cmd + Shift + R
Windows: Ctrl + F5
```

## 页面入口

```text
首页/菜单       http://127.0.0.1:5176/
短线右侧       http://127.0.0.1:5176/#/short-term
长期价投       http://127.0.0.1:5176/#/market
政策解读       http://127.0.0.1:5176/#/policy
特别关注       http://127.0.0.1:5176/#/watchlist
交易复盘       http://127.0.0.1:5176/#/trade-review
规则目录       http://127.0.0.1:5176/#/rules
系统配置       http://127.0.0.1:5176/#/settings
```

## 基本使用流程

### 短线右侧

1. 打开 `短线右侧`。
2. 检查阈值配置，例如候选数量、扫描数量、K 线复核数、追涨上限、财报分下限。
3. 点击 `重新扫描` 发起手动扫描。
4. 查看候选列表，默认按推荐优先级排序。
5. 进入候选详情，检查推荐理由、风险观察、筹码结构、资金流和证据缺口。
6. 需要跟踪时加入 `特别关注` 或 `交易复盘`。

当前短线常用默认值：

```text
K线复核数：120
追涨上限：6.5%
扫描数量：6000
候选数量：8
```

短线结果应重点看：

- 是否处于可执行动作层。
- 是否有主力资金流入和上方抛压缓解。
- 筹码是否主要集中在低位，且高位筹码是否被消化。
- 是否存在公告、财报、估值、行业对比等反证。

### 长期价投

1. 打开 `长期价投`。
2. 查看样本列表，列表按推荐指数排序并分页展示。
3. 点击公司卡片进入详情。
4. 阅读推荐理由、风险观察、财报质量、估值对比、公告证据和缺失证据。
5. 对需要持续跟踪的公司加入 `特别关注`。

### 政策解读

政策类属于长线信息，不需要每次刷新页面都重新跑。建议每天自动跑一次，并将结果持久化，形成可追溯知识库。

使用方式：

1. 打开 `政策解读`。
2. 查看最新政策主题和产业影响。
3. 点击主题进入相关公司、行业和证据链。
4. 对重要主题加入后续研究或特别关注。

### 交易复盘

1. 在推荐卡上点击 `加入复盘`。
2. 进入 `交易复盘` 页面。
3. 录入买入/卖出成交。
4. 查看持仓、已实现收益、未实现收益和阶段性表现。
5. 通过复盘结果观察策略有效性。

## 配置说明

常用配置位置：

```text
系统配置页                           大模型与政策源动态配置
runtime_config_section               动态配置数据库表
apps/api/src/main/resources          其余稳定的 Spring Boot 配置
```

AI 配置推荐使用 DeepSeek，可在系统配置页设置：

```text
provider=deepseek
model=deepseek-v4-pro
baseUrl=https://api.deepseek.com
responseFormat=json_object
```

密钥推荐通过环境变量维护：

```text
DEEPSEEK_API_KEY=你的密钥
```

不要提交 `.env.local`、真实 Token、真实数据库密码。

## 数据持久化

Docker 默认使用 H2 文件库并挂载到 `api-data` 卷，适合本地持续运行。

如需 PostgreSQL：

```bash
docker compose --profile infra up -d postgres

SPRING_PROFILES_ACTIVE=prod \
JDBC_DATABASE_URL=jdbc:postgresql://127.0.0.1:5432/aistock \
JDBC_DATABASE_DRIVER=org.postgresql.Driver \
JDBC_DATABASE_USERNAME=aistock \
JDBC_DATABASE_PASSWORD=aistock \
mvn -pl apps/api spring-boot:run
```

核心历史表包括：

```text
market_kline_history
research_analysis_history
investment_decision_history
trade_cases / trade_fills
```

## 常用命令

后端测试：

```bash
mvn -pl apps/api test
```

前端测试：

```bash
cd apps/web-react
npm test -- --run
```

前端构建：

```bash
cd apps/web-react
npm run build
```

后端打包：

```bash
mvn -pl apps/api -DskipTests package
```

重建应用容器：

```bash
docker compose up -d --build api web
```

查看日志：

```bash
docker compose logs -f api
docker compose logs -f web
```

停止应用：

```bash
docker compose down
```

## 数据源

当前系统主要使用：

```text
东方财富 A 股行情：
https://push2.eastmoney.com/api/qt/clist/get

东方财富主力资金流：
https://push2.eastmoney.com/api/qt/ulist.np/get
https://push2.eastmoney.com/api/qt/stock/fflow/kline/get

东方财富行业资金流：
https://push2.eastmoney.com/api/qt/clist/get

东方财富日 K / 换手率：
https://push2his.eastmoney.com/api/qt/stock/kline/get

东方财富财务指标：
https://datacenter-web.eastmoney.com/api/data/v1/get

巨潮资讯公告：
https://www.cninfo.com.cn/new/hisAnnouncement/query

政策源：
中国政府网、国家发改委、工信部、科技部、财政部、国家能源局、证监会、生态环境部、农业农村部、交通运输部
```

## 常见问题

### 页面仍显示旧参数

先确认是否重新构建了 React：

```bash
cd apps/web-react
npm run build
docker compose up -d --build web
```

然后强刷浏览器。

### 系统配置保存后未生效

检查以下信息：

```text
GET /api/runtime-config 返回的 storage 是否为 database
对应栏目 revision 是否已增加
前后端是否连接同一个 H2 卷或 PostgreSQL 实例
GET /api/ai/llm-config 是否已返回新的有效模型
```

详细说明见 `docs/runtime-config.md`。

### AI 分析不可用

检查：

```text
DeepSeek Key 是否配置
provider/model/base-url 是否匹配
接口是否要求 json_object 输出
```

### 手动短线扫描很慢

短线会拉全市场行情、K 线、财报、资金流和筹码数据。扫描数量、K 线复核数越大，耗时越长。页面刷新不会自动重新扫描，手动点击 `重新扫描` 才会触发流程。

## 提交代码

初始化远程仓库示例：

```bash
git init
git add .
git commit -m "first commit"
git branch -M main
git remote add origin https://github.com/LingDaoyun/shares-test.git
git push -u origin main
```

如果仓库已经存在，只需要确认 remote 后提交并推送。
