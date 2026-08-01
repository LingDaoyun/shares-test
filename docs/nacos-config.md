# Nacos 配置中心与服务发现

## 定位

项目默认使用 Nacos：

```text
Nacos Config: 大模型 Provider、模型名、规则阈值、风控开关、采集频率、数据源开关
Nacos Discovery: market-data-service、policy-service、ai-agent-service 等微服务注册发现
```

如果要绕过 Nacos，可显式使用：

```bash
SPRING_PROFILES_ACTIVE=local mvn -pl apps/api spring-boot:run
```

## 本地 Nacos

你已有测试 Nacos 时，直接设置：

```bash
NACOS_SERVER_ADDR=127.0.0.1:8848
NACOS_GROUP=AI_STOCK
```

没有 Nacos 时，也可以用项目内 docker-compose 示例：

```bash
docker compose --profile infra up -d nacos
```

控制台：

```text
http://127.0.0.1:8848/nacos
```

当前 docker-compose 为开发便利关闭了 Nacos 鉴权。生产环境必须开启鉴权，并把高敏密钥放在 KMS 或云厂商密钥管理服务。

## 配置 Data ID

推荐配置：

```text
Data ID: ai-stock-api.yml
Group: AI_STOCK
Format: YAML
```

示例内容见：

```text
infra/nacos/ai-stock-api.yml
```

`application-nacos.yml` 还会按 `APP_ENV` 额外读取环境配置：

```text
ai-stock-api-${APP_ENV}.yml
```

默认 `APP_ENV=local`。

## 启动后端并注册服务

```bash
NACOS_SERVER_ADDR=127.0.0.1:8848 \
NACOS_GROUP=AI_STOCK \
./scripts/run-api-local.sh
```

启动后注册服务名：

```text
ai-stock-api
```

后续拆分微服务时，服务名建议保持领域清晰：

```text
market-data-service
policy-service
document-service
company-service
factor-service
rule-engine-service
backtest-service
ai-agent-service
```

## 密钥边界

OpenAI Key 可以从 Nacos 注入，但更推荐：

```text
Nacos 保存普通配置和密钥引用
KMS/环境变量保存真实密钥
```

当前示例里 `research.ai.llm.api-key` 默认继续引用环境变量：

```yaml
research:
  ai:
    llm:
      provider: deepseek
      api-key: ${DEEPSEEK_API_KEY:}
      model: deepseek-v4-pro
      base-url: https://api.deepseek.com
      response-format: json_object
```

这样既能统一配置中心，又不会把真实 Key 明文固化到仓库。

## 政策源配置

政策采集源也走 Nacos，可实时增删来源、调整权重：

```yaml
research:
  live-data:
    policy-sources:
      - name: 中国政府网
        type: json
        url: https://www.gov.cn/zhengce/zuixin/ZUIXINZHENGCE.json
        weight: 100
      - name: 国家发展改革委
        type: html
        url: https://www.ndrc.gov.cn/xxgk/zcfb/ghwb/
        weight: 92
      - name: 工业和信息化部
        type: html
        url: https://www.miit.gov.cn/zwgk/zcwj/
        weight: 90
      - name: 生态环境部
        type: html
        url: https://www.mee.gov.cn/xxgk/
        weight: 84
```

代码会按来源轮询保留样本，避免高权重来源把其他部委全部挤掉。`weight` 用于置信度和展示排序，不再作为唯一截断依据。

## 行情与财务数据开关

开发联调建议保持快速公司列表，先拉公开行情和基础画像，不在列表刷新时同步等待东方财富年报指标：

```yaml
research:
  live-data:
    fast-company-list: true
    eastmoney-fund-flow-url: https://push2.eastmoney.com/api/qt/ulist.np/get
    eastmoney-fund-flow-minute-url: https://push2.eastmoney.com/api/qt/stock/fflow/kline/get
    cninfo-announcement-url: https://www.cninfo.com.cn/new/hisAnnouncement/query
    filing-limit: 12
    filing-pdf-parse-limit: 2
    filing-pdf-max-pages: 6
```

当你要做完整财务因子重算、批量观察池刷新或离线校验时，再切换为：

```yaml
research:
  live-data:
    fast-company-list: false
```

这样公司列表、详情研究页和观察池可以先保持响应速度，完整财报数据采集后续适合拆到独立批处理或 market-data-service。

周期交易/左侧试仓池会额外读取东方财富资金流，作为“量能/资金”评分和证据项：

```yaml
research:
  live-data:
    eastmoney-fund-flow-url: https://push2.eastmoney.com/api/qt/ulist.np/get
    eastmoney-fund-flow-minute-url: https://push2.eastmoney.com/api/qt/stock/fflow/kline/get
```

这两个配置缺失时后端会使用内置默认值。东财 push2/push2his 系列盘中可能触发连接级风控，系统已加入串行节流和重试；如果仍失败，会在候选证据里保留数据缺口，不会用空资金流强行打分。

## 短线筹码结构与外部认证

短线 V3 在 Java 内使用近 120 根日 K、换手率和 150 个价格桶复算成本分布。腾讯前复权日线仍作为价格主序列，东方财富按交易日补齐换手率；Tushare `cyq_perf` 只认证最近完整交易日，不替代本地计算，也不参与全局数据质量门禁。

在现有 `ai-stock-api.yml` 的 `research` 下加入：

```yaml
research:
  short-term:
    chip:
      enabled: true
      lookback-bars: 120
      price-buckets: 150
      min-valid-bars: 80
      min-turnover-coverage: 0.95
      weight: 0.25
      activation-mode: SHADOW
      single-source-coefficient: 0.60
      max-average-cost-deviation: 0.03
      min-cost-band-overlap: 0.70
      max-winner-rate-deviation: 0.10
      tushare:
        enabled: true
        base-url: https://api.tushare.pro
        token: ${TUSHARE_TOKEN:}
        connect-timeout-ms: 1200
        read-timeout-ms: 1800
        max-concurrency: 4
```

`activation-mode` 目前仅作为兼容配置保留，短线主排序始终不混入筹码分：

```text
OFF     不计算筹码结构诊断
SHADOW  计算筹码成本分布画像，正式排序仍沿用金叉、量能、换手和收盘强度主分（默认）
ACTIVE  兼容旧配置；当前版本不会采用筹码分改写短线主排序
```

没有 Tushare token、接口限流、认证冲突或数据过期时，只把该候选的认证系数降为零或单源系数，不会让整轮扫描进入 `DATA_BLOCKED`。真实 token 推荐通过容器环境变量 `TUSHARE_TOKEN` 注入；若直接写入本地 Nacos，也不要提交到 Git。

公告证据会优先查询巨潮公告列表。当前版本会对可匹配证券内部编码的样本返回真实公告，并默认解析少量公告 PDF 的前几页，抽取风险事件、壁垒线索和兑现线索；无法匹配或接口失败时，研究视图会降级使用公司画像里的年报/公告证据，并在 `dataGaps` 中提示需要补齐巨潮、上交所、深交所、北交所公告源。在线解析参数要保守，完整 PDF 解析适合迁到批处理或文档库。

DeepSeek、OpenAI 与 Kimi/Moonshot 都走 OpenAI 兼容协议，可以只改 Nacos 配置切换：

```yaml
research:
  ai:
    llm:
      provider: openai
      api-key: ${OPENAI_API_KEY:}
      model: gpt-5.5
      base-url: https://api.openai.com/v1
```

切换到 DeepSeek 开放平台时推荐单独发布本地覆盖配置，避免覆盖主配置：

```text
Data ID: ai-stock-api-local.yml
Group: AI_STOCK
Format: YAML
```

内容：

```yaml
research:
  ai:
    llm:
      provider: deepseek
      api-key: ${DEEPSEEK_API_KEY:}
      api-key-env: DEEPSEEK_API_KEY
      model: deepseek-v4-pro
      base-url: https://api.deepseek.com
      response-format: json_object
      strict-json-schema: false
      max-completion-tokens: 8192
```

注意：Kimi Code 文档中的 `https://api.kimi.com/coding/v1` / `kimi-for-coding` 是编码 Agent 专用接口。普通业务后端直接调用可能返回 `403 access_terminated_error`，因此本平台的 Kimi 通用接入应走 Moonshot/Kimi 开放平台配置。

配置后可以检查当前运行时模型和 Key 是否被后端读取，不会暴露真实 Key：

```bash
curl http://127.0.0.1:19080/api/ai/llm-config
```

返回结果里的两个字段最关键：

```text
apiKeyConfigured=true  表示后端已经拿到 Key
apiKeySource=...       表示 Key 来自 Nacos 配置或环境变量
```

如果 `apiKeyConfigured=false`，通常不是模型接口问题，而是配置没有进入当前进程。按下面顺序排查：

```text
1. 后端默认读取 namespace 为空，即 Nacos 控制台里的 public/default。
2. 默认 Group 是 AI_STOCK，不是 DEFAULT_GROUP。
3. 默认 Data ID 是 ai-stock-api.yml，并额外读取 ai-stock-api-local.yml。
4. 如果 Nacos 配置里写 api-key: ${DEEPSEEK_API_KEY:}，还需要启动后端进程时真的有 DEEPSEEK_API_KEY 环境变量。
5. 如果想完全通过 Nacos 配置中心注入，可以把 api-key 直接配置成真实 Key；生产环境更建议配置密钥引用并从 KMS 注入。
```

本地可以用 Nacos OpenAPI 快速确认配置是否在正确位置，注意不要把真实 Key 打印到聊天或日志里：

```bash
curl 'http://127.0.0.1:8848/nacos/v1/cs/configs?dataId=ai-stock-api.yml&group=AI_STOCK'
```

Agent 共识增强使用同一套 LLM 配置：

```text
GET  /api/companies/{symbol}/agent-consensus/prompt
POST /api/companies/{symbol}/agent-consensus/ai
GET  /api/selection/agent-shortlist
```

`/agent-consensus/ai` 是手动触发接口。它先生成确定性五 Agent 共识，再调用模型补充各 Agent 的论证、反证和信心说明；如果 Key 缺失或模型调用失败，会保留确定性共识并在 `aiWarnings` 中返回原因。

`/selection/agent-shortlist` 不按股票代码前缀过滤。它从当前全市场公司池中复核候选，逐只运行五 Agent 共识，并返回可追溯 trace。这个接口默认不主动调用大模型，避免批量 shortlist 时消耗过多额度；单只股票仍可进入详情页手动执行 AI 辩论增强。

仓库里也提供了同样的覆盖文件：

```bash
NACOS_CONFIG_DATA_ID=ai-stock-api-local.yml \
NACOS_CONFIG_FILE=infra/nacos/ai-stock-api-local-deepseek.yml \
./scripts/publish-nacos-config.sh
```
