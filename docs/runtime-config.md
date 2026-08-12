# 数据库运行配置

系统的动态配置存储在当前 H2 或 PostgreSQL 数据库的 `runtime_config_section` 表中，不依赖独立配置中心。

## 配置栏目

| 栏目 | 内容 | 生效时机 |
| --- | --- | --- |
| `LLM` | Provider、模型、Base URL、输出格式、Key 引用等 | 事务提交后，下一次模型调用 |
| `POLICY_SOURCES` | 政策源名称、类型、URL 和权重 | 事务提交后，下一轮政策抓取 |

每个栏目单独保存 JSON、`revision` 和数据库更新时间。同一栏目并发写入由数据库行锁串行化，不同栏目互不改写。应用启动时只初始化缺失栏目，不覆盖已有数据。

## 管理接口

```text
GET  /api/runtime-config
PUT  /api/runtime-config
GET  /api/runtime-config/llm
PUT  /api/runtime-config/llm
GET  /api/runtime-config/policy-sources
PUT  /api/runtime-config/policy-sources
GET  /api/ai/llm-config
```

`GET /api/runtime-config` 应返回：

```json
{
  "storage": "database",
  "llmRevision": 0,
  "policySourcesRevision": 0
}
```

响应还包含脱敏后的模型配置、政策源和更新时间。保存成功以数据库事务提交为准；无需重启应用，也无需等待配置广播。

## API Key 语义

- 保存时 `apiKey` 为空或全空白：保留数据库中已有 Key。
- 保存时填写 `apiKey`：更新数据库中的直接 Key。
- 所有 GET 和 PUT 响应中的 `apiKey` 都是 `null`，只返回 `apiKeyConfigured` 与 `apiKeySource`。
- 模型调用的解析顺序是：数据库直接 Key、配置指定的环境变量、Provider 默认环境变量。
- 部署优先使用 `DEEPSEEK_API_KEY`、`OPENAI_API_KEY`、`MOONSHOT_API_KEY` 或 `KIMI_API_KEY` 等环境变量。数据库直接 Key 仅用于兼容场景。

数据库文件、数据库账号和备份应按可能包含密钥的数据管理。不要在日志、问题单或诊断输出中打印 `payload_json`。

## 诊断

1. 请求 `GET /api/runtime-config`，确认 `storage=database` 以及目标栏目修订号。
2. 保存后再次读取，确认目标栏目 `revision` 已增加，另一栏目修订号未变化。
3. 请求 `GET /api/ai/llm-config`，确认有效 Provider、模型和 Key 来源。
4. Docker 默认数据库位于 `api-data` 命名卷；重建容器时不要删除该卷。
5. PostgreSQL 部署要确认所有 API 实例连接同一个数据库。

配置解析损坏时服务会明确报错，不会静默覆盖为默认值。需要修复时先备份数据库，再通过受控 SQL 或管理接口处理目标栏目。
