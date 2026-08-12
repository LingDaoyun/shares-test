# 数据库存储动态系统配置设计

## 背景与结论

当前工程是模块化单体：一个 Spring Boot API、一个 React Web，以及同一进程内的领域模块。代码没有 Feign、负载均衡客户端或服务发现消费者，但默认 Profile、后端依赖、Docker Compose 和配置保存接口仍依赖 Nacos。

当前运行实例没有启动 Nacos，API 与 Web 健康检查仍然正常；对 `PUT /api/runtime-config/llm` 提交当前模型配置会稳定返回 `503`，错误为 `Nacos 配置读取失败`。这说明 Nacos 已经变成只在配置写入时暴露的脆弱依赖，而不是当前系统正常运行所必需的基础设施。

工程已经把数据库作为核心依赖：Docker 默认使用命名卷中的持久化 H2 文件，生产 Profile 支持 PostgreSQL，业务数据通过 JPA 和统一 `schema.sql` 管理。因此本次删除 Nacos，并把系统配置迁移到现有数据库。该方案不增加新的基础设施，配置保存后立即对下一次业务调用生效。

## 目标

- 彻底移除当前运行链路中的 Nacos Config 与 Nacos Discovery。
- 大模型配置和政策源配置分别读取、重置、保存，保存后无需重启即可生效。
- 配置随 H2/PostgreSQL 数据库持久化，API 容器重启后继续有效。
- 保存一个栏目时不覆盖另一个栏目。
- 大模型 API Key 留空时保留已有值，响应、日志和异常中不得返回真实 Key。
- 保留现有确定性投研、短线 V4、点时和终态门禁行为；模型配置迁移不得影响规则计算。
- H2 与 PostgreSQL 使用同一套表结构和 JPA 代码。

## 非目标

- 不把短线策略、筹码、调度或验证参数加入系统配置页面。
- 不新增微服务、Redis 配置广播、消息队列或新的配置中心。
- 不新增配置审批、历史版本回滚或多人编辑界面。
- 不修改模型输出只能进入证据层、不能绕过确定性规则的既有边界。
- 不导入、读取或打印任何现存 Nacos 或本机环境中的真实密钥。

## 方案选择

考虑过三种实现：

1. 复用数据库，按栏目存储配置。数据库已经是系统必需依赖，事务、持久化、容器重启恢复和 H2/PostgreSQL 兼容都可复用。本方案被采用。
2. 外部 YAML 文件加文件监听。它需要处理原子替换、权限、损坏恢复、跨实例同步和容器卷路径；在已有数据库的前提下没有收益。
3. 保留 Nacos 并修复连接。当前没有服务发现消费者，仍会保留额外进程、双 Data ID 覆盖和运行健康但配置不可写的分裂状态，因此被拒绝。

## 数据模型

新增表 `runtime_config_section`，每个可动态修改的栏目占一行：

```sql
CREATE TABLE IF NOT EXISTS runtime_config_section (
    section_key VARCHAR(64) PRIMARY KEY,
    payload_json TEXT NOT NULL,
    revision BIGINT NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);
```

首期只允许两个 `section_key`：

- `LLM`：大模型 Provider、模型、Base URL、响应格式、结构化输出开关、thinking、最大输出 Token、Temperature、API Key 及 API Key 环境变量名。
- `POLICY_SOURCES`：按用户顺序保存的政策源列表。

`payload_json` 使用普通 `TEXT`，避免依赖 PostgreSQL 专属 JSON 类型；Java 使用 Jackson 做强类型序列化和反序列化。`revision` 每次成功保存加一，`updated_at` 使用服务器当前时间。栏目分行保证大模型与政策源的独立事务边界。

数据库内直接配置的 API Key 会包含在 `LLM` 行的 JSON 中，但不得进入任何 API 响应、日志、异常、测试快照或配置修订元数据。部署仍优先使用 `apiKeyEnv` 指向环境变量；数据库访问权限和备份必须按包含密钥的数据处理。

## 组件边界

### `RuntimeConfigSectionEntity` 与 Repository

实体只负责数据库映射，不承载默认值、密钥解析或业务校验。Repository 提供普通读取和带 `PESSIMISTIC_WRITE` 的栏目读取，确保同一栏目并发保存时串行提交。

### `RuntimeConfigDefaults`

唯一职责是提供服务端首次启动默认值：

- DeepSeek、`deepseek-v4-pro`、`https://api.deepseek.com`、`json_object`、最大输出 8192。
- 当前十个内置政策来源。

默认值不包含真实 API Key。初始化器仅在对应栏目不存在时插入默认行，绝不覆盖已有数据库配置。

### `RuntimeConfigStore`

负责栏目读取、强类型解析、事务更新和修订号。公开接口按栏目区分：

```java
StoredLlmConfig readLlm();
StoredLlmConfig updateLlm(LlmRuntimeConfig request);
List<PolicySourceConfig> readPolicySources();
List<PolicySourceConfig> updatePolicySources(List<PolicySourceConfig> request);
RuntimeConfigSnapshot readSnapshot();
RuntimeConfigSnapshot updateSnapshot(RuntimeConfigSnapshot request);
```

`updateLlm` 在锁定 `LLM` 行后处理 API Key 留空语义、校验并提交。`updatePolicySources` 只锁定 `POLICY_SOURCES`。兼容用的整份更新按固定顺序先锁 `LLM`、再锁 `POLICY_SOURCES`，防止死锁。

### `LlmSettingsProvider`

统一替代 `LlmTrendAnalysisService` 与 `LlmChatClient` 内重复的模型配置解析逻辑。每次模型调用开始时读取一次不可变设置快照，整次调用都使用同一 Provider、模型、Base URL 和 Key，避免调用过程中配置切换造成混用。

API Key 解析顺序保持明确：数据库中直接保存的 Key、配置指定的环境变量、Provider 默认环境变量。`LlmSettingsProvider` 只向模型客户端返回内部设置；公开预览只返回 `apiKeyConfigured` 和 `apiKeySource`。

### `RuntimeConfigService`

保留现有 Controller 的业务门面，但删除 Nacos HTTP 客户端、YAML 合并、Data ID、Group、Namespace 和远端发布逻辑。它只调用 `RuntimeConfigStore` 并组装不含密钥的 DTO。

### 政策采集

`GovPolicyClient` 不再从启动时绑定的 `LiveDataProperties.policySources` 读取列表，而是在每轮采集开始时从 `RuntimeConfigStore` 获取一次不可变列表。一次采集过程使用同一快照；下一轮自动使用最新保存结果。其他行情、公告和财务 URL 继续由 `application.yml` 与 `LiveDataProperties` 管理。

## API 兼容与响应

以下栏目接口路径保持不变：

| 方法 | 路径 | 行为 |
| --- | --- | --- |
| GET | `/api/runtime-config/llm` | 返回当前有效大模型配置，不返回 Key |
| PUT | `/api/runtime-config/llm` | 事务更新 `LLM` 行并返回生效配置 |
| GET | `/api/runtime-config/policy-sources` | 返回当前政策源列表 |
| PUT | `/api/runtime-config/policy-sources` | 事务更新 `POLICY_SOURCES` 行 |
| GET | `/api/runtime-config` | 返回两个栏目和数据库存储元数据 |
| PUT | `/api/runtime-config` | 兼容旧前端，在一个事务中更新两个栏目 |

`RuntimeConfigSnapshot` 删除 Nacos 专属的 `dataId` 和 `group`，增加：

- `storage`：固定为 `database`。
- `llmRevision`：大模型栏目修订号。
- `policySourcesRevision`：政策源栏目修订号。
- `updatedAt`：两个栏目的最大更新时间。

`LlmRuntimeConfig.apiKey` 只作为写入字段。所有 GET、PUT 响应都将其置为 `null`。空值或全空白表示保留数据库中已有 Key，不表示删除。

## 保存与生效数据流

大模型保存链路：

```text
React/Vue 表单
-> PUT /api/runtime-config/llm
-> Bean Validation
-> 锁定 LLM 行
-> 合并“空 Key 保留”语义
-> JSON 序列化
-> revision + 1 并提交事务
-> 返回脱敏配置
-> 下一次模型调用从数据库读取新快照
```

政策源链路相同，但只更新 `POLICY_SOURCES` 行。配置读取量相对行情和模型网络调用极小，因此首期不增加进程缓存；每次业务操作直接读取数据库，可以自然支持将来多个 API 实例共享同一配置，且无需广播失效消息。

## 校验、并发与错误处理

- 大模型沿用现有校验：Provider、模型、Base URL、Response Format 必填，最大 Token 为正数。
- Base URL 必须是合法的 `http` 或 `https` 绝对地址。
- Temperature 为空或在 `0..2` 范围内。
- 政策源名称、类型、URL 必填，URL 必须是 `http` 或 `https`，权重为 `1..100`。
- 空政策源列表合法，表示显式禁用政策抓取来源。
- JSON 解析失败视为数据库配置损坏，返回明确服务错误，不静默退回默认值并覆盖坏数据。
- 更新过程中的校验、序列化或数据库异常会回滚事务，原配置和原修订号保持不变。
- 同一栏目并发保存采用数据库行锁，最后一个完成锁等待并成功提交的请求生效；不同栏目可独立更新。
- 模型或政策调用只在操作开始时读取一次配置快照，不在一个操作中途切换。

## 启动与迁移

应用启动时执行以下顺序：

1. `schema.sql` 创建 `runtime_config_section`。
2. 初始化器检查 `LLM` 与 `POLICY_SOURCES` 行。
3. 缺失行使用无密钥默认值插入；已存在行保持不变。
4. 初始化完成后，运行时配置 API 和业务消费者统一读取数据库。

当前 Nacos 未运行，因此本次不尝试从 Nacos 抓取或迁移配置，也不接触任何真实密钥。大模型与政策源初值以当前代码中的有效默认值为准。短线、调度、筹码和验证参数保留在 `application.yml` 或现有代码默认值中；删除 dormant Nacos 文件前必须逐项比对当前有效值，不能把未生效的 Nacos 覆盖值误当成现网值写回，从而改变 V4 行为。

## Nacos 删除范围

运行代码与部署配置中删除：

- Maven 的 Nacos Config、Nacos Discovery 依赖。
- `@EnableDiscoveryClient`。
- `application-nacos.yml`、默认 `nacos` Profile 和 `spring.cloud.nacos` 配置。
- Docker Compose 的 Nacos 环境变量、Nacos 服务和 `nacos-data` 卷。
- `scripts/publish-nacos-config.sh` 与 `infra/nacos/` 运行配置。
- `RuntimeConfigServiceTest` 中的伪 Nacos HTTP Server。
- React 与旧 Vue 设置页中的“保存到 Nacos”、Data ID、Group 和 Nacos Key 提示。
- README、当前架构文档和运行手册中把 Nacos 描述为当前依赖的内容。

历史设计/计划文档保留原始上下文，不批量改写历史事实；新的设计文档明确取代它们的当前运行结论。

## 前端交互

React 主界面和仓库中的旧 Vue 界面统一调整：

- 操作按钮文案改为“保存配置”。
- 成功提示改为“配置已保存并生效”。
- API Key 提示改为“留空则保留数据库中已有 Key”。
- 移除 Data ID / Group 标签，显示“数据库配置”及修订号。
- 两个栏目继续维护独立的 loading/saving 状态。
- 保存失败时保留用户输入并显示后端明确错误。
- 重置仍只修改本地表单，用户点击保存后才写入数据库。

## 测试策略

后端按测试先行覆盖：

- 首次启动只创建缺失栏目，不覆盖已有配置。
- 保存模型立即改变 `LlmSettingsProvider` 的下一次读取结果。
- API Key 留空保留旧值；填写时更新，但响应与日志不包含明文。
- 保存模型不修改政策源行或其修订号。
- 保存政策源不修改模型行或其修订号。
- 校验或序列化失败时事务回滚。
- H2 行锁/并发测试证明同栏目不会产生半写入。
- Controller 的 GET/PUT 响应不泄露 API Key。
- `GovPolicyClient` 的下一轮采集使用最新政策源。
- `LlmTrendAnalysisService` 与 `LlmChatClient` 使用统一设置提供者。

前端测试覆盖：

- 页面不再显示 Nacos、Data ID 或 Group。
- 两个栏目继续独立读取、重置和保存。
- 保存提示为数据库动态配置语义。
- 保存模型后刷新模型预览。
- 保存失败保留当前编辑内容。

仓库级验证包括：

- 后端定向配置测试与完整 Maven 测试。
- React 设置页测试、完整测试、类型检查和生产构建。
- 旧 Vue 的类型检查/构建（若其现有脚本支持）。
- `docker compose config` 不包含 Nacos 服务或变量。
- 全仓运行配置扫描确认生产代码、Compose、脚本和当前文档不再依赖 Nacos。

## 运行验收

在不启动 `8848` 端口的情况下完成：

1. 重建并仅重启本项目 API/Web，保护其他项目容器。
2. 健康检查通过。
3. 读取当前模型配置并记录非敏感字段。
4. 保存一个可识别的测试模型名，确认 PUT 返回 200，模型预览立即显示新值。
5. 重启 API，确认测试模型名仍存在，证明数据库持久化有效。
6. 恢复原模型名并确认生效。
7. 对政策源执行同类的保存、读取和恢复验证，不打印任何密钥。
8. 验证短线核心接口仍启动正常，并运行 V4 相关回归测试；健康状态不能替代门禁回归证据。

## 验收标准

- Nacos 未安装、未启动且端口不可用时，系统配置读取和保存均成功。
- 模型配置保存后，下一次模型配置预览与模型客户端均使用新值，无需重启。
- 政策源保存后，下一轮政策采集使用新列表，无需重启。
- API 容器重启后两个栏目保持最后一次成功配置。
- 任一栏目保存不会修改另一栏目的内容或修订号。
- API Key 不出现在任何响应、日志或测试输出中。
- Maven、React 和适用的 Vue 回归全部通过。
- 短线 V4 的点时、95% 覆盖、透明贡献、T1/T2 闭环、`FINAL_PENDING` 数据库时间认证和 legacy `FINAL_READY` fail-closed 行为没有被修改。
