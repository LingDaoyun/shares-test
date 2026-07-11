# AI 趋势分析提示词设计

## 定位

这个节点用于分析政府规划文件、产业报告、新闻和公司公告。它不是摘要器，也不是荐股器，而是把长文本转成可审计的长期趋势假设。

核心目标：

```text
原文证据 -> 显性信号 -> 隐含趋势 -> 产业链环节 -> 公司筛选画像 -> 监控指标 -> 反证条件
```

## 为什么要这样写 Prompt

长线投研最怕两件事：

- 模型把政策口号复述成“行业利好”，没有产业机制。
- 模型直接跳到公司和买卖结论，缺少证据链和反证。

所以 Prompt 必须强制模型回答这些问题：

- 文件到底明确说了什么？
- 哪些是模型基于机制推导出来的趋势？
- 这个趋势靠什么兑现：财政、采购、标准、监管、技术路线、国产替代、成本下降，还是商业模式变化？
- 哪些产业链环节可能受益，哪些可能受损？
- 后面要看哪些公告、年报字段、招投标、价格、订单、毛利率、现金流才能验证？
- 出现什么情况时，这个趋势假设应该被降权？

## 输出契约

后端已经提供 Prompt 预览接口：

```text
GET  /api/ai/trend-prompts/sample
POST /api/ai/trend-prompts/preview
POST /api/ai/trend-analysis
```

其中 `/api/ai/trend-analysis` 会真实调用当前配置的大模型 Provider，并要求模型按严格 JSON Schema 输出。

当前支持 OpenAI 兼容协议，可通过 Nacos 切换：

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

实际模型调用时，应要求模型只输出 JSON，并包含以下结构：

```text
document_fingerprint       文档指纹
explicit_signals           原文显性信号
agent_cross_checks         多 Agent 交叉验证结论
hidden_trends              隐含趋势
industry_chain_map         产业链拆解
company_research_tasks     后续公司研究任务
monitoring_indicators      监控指标
counter_evidence           反证条件
evidence_gaps              证据缺口
overall_assessment         总体判断
```

`agent_cross_checks` 不是让模型输出长篇对话，而是把多个研究角色的结论固化为可审计字段：

```text
政策原文核验 -> 产业链机制推导 -> 财政/统计/订单验证 -> 风险反证 -> A股映射 -> 裁判汇总
```

每个角色只输出 `SUPPORTED/PARTIAL/WEAK/CONFLICTED`、证据引用、置信度和仍需验证的疑点，避免把一条政策标题直接放大成投资结论。

## 模型选择

这个节点属于高价值、低频、强推理任务，建议优先使用能力更强的推理模型，而不是最低成本模型。原因是它要处理：

- 长文档理解。
- 隐含趋势推导。
- 反证和证据缺口识别。
- 严格结构化输出。

落地时不要把模型名写死在代码里，统一走 Nacos/环境变量：

```text
research.ai.llm.provider=deepseek | openai | moonshot | kimi-code
research.ai.llm.api-key=...
research.ai.llm.model=deepseek-v4-pro | deepseek-v4-flash | gpt-5.5 | kimi-k2.6 | kimi-for-coding
research.ai.llm.base-url=https://api.deepseek.com | https://api.openai.com/v1 | https://api.moonshot.ai/v1 | https://api.kimi.com/coding/v1
```

DeepSeek 暂按 JSON Object 模式接入，严格字段校验由后端解析 JSON 和提示词 schema 共同承担。

`kimi-code` 仅用于对齐 Kimi Code 编码 Agent 接口，不建议作为投研平台后端的通用模型入口。

如果接 Spring AI Alibaba 或 AgentScope Java，也应复用同一份 Prompt 和 JSON schema。

## API Key 原则

API Key 不进入 Git，不写入配置文件，不写入前端。后端只能从环境变量或密钥管理服务读取。

当前实现已经接入可配置模型调用入口。如果运行环境没有 LLM API Key，服务会直接阻断并提示配置 Key。

## 投研边界

模型输出只能进入证据层和趋势假设层，不能绕过规则引擎直接形成买卖决策。

```text
AI 输出 -> 证据库/趋势库 -> 因子计算 -> 规则引擎 -> 观察池
```

最终是否进入观察池，仍由可版本化规则、风控规则和人工复核共同决定。
