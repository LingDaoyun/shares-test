# 配置中心栏目独立保存设计

## 背景

React 配置页当前将大模型配置和政策源配置组装成一份 `RuntimeConfigSnapshot`，共用一组读取、重置和保存按钮。修改任意栏目都会提交整份配置，存在误覆盖另一个栏目的风险；两个栏目也共用保存状态，无法独立操作。

本次改造将配置页调整为栏目级读取、重置和保存。首期栏目为“大模型配置”和“政策源配置”，接口和前端状态边界应允许后续继续增加行情源、短线策略等配置栏目。

## 目标

- 大模型配置和政策源配置分别读取、重置、保存。
- 保存一个栏目时，不修改 Nacos 中其他栏目或未知 YAML 节点。
- 大模型 API Key 留空时保留已有值，填写时才替换。
- 每个栏目独立显示读取中、保存中、成功和失败反馈。
- 重置只修改当前栏目的本地表单，用户再次点击保存后才写入 Nacos。
- 保留现有整份配置读取接口，兼容应用启动和其他调用方。

## 非目标

- 本次不新增配置版本管理、审批、回滚或多人编辑锁。
- 本次不修改 Nacos dataId、group 或短线调度的 local 覆盖配置。
- 本次不迁移或展示 API Key 明文。

## 后端接口

在现有 `/api/runtime-config` 下增加四个栏目接口：

| 方法 | 路径 | 请求 | 响应 |
| --- | --- | --- | --- |
| GET | `/api/runtime-config/llm` | 无 | `LlmRuntimeConfig` |
| PUT | `/api/runtime-config/llm` | `LlmRuntimeConfig` | 更新后的 `LlmRuntimeConfig` |
| GET | `/api/runtime-config/policy-sources` | 无 | `List<PolicySourceConfig>` |
| PUT | `/api/runtime-config/policy-sources` | `List<PolicySourceConfig>` | 更新后的政策源列表 |

现有 `GET /api/runtime-config` 和 `PUT /api/runtime-config` 暂时保留，避免破坏兼容性；React 设置页切换到栏目接口。

`PUT /llm` 只更新 `research.ai.llm`。`PUT /policy-sources` 只更新 `research.live-data.policy-sources`。两者都先读取 Nacos 当前 YAML，在原 Map 上合并目标节点后发布，必须保留以下内容：

- 另一个配置栏目。
- `research.short-term` 等已知但不归当前栏目所有的配置。
- 当前代码不了解的未来节点。

当 API Key 为空或全空白时，不写入 `api-key`，保留 Nacos 原值。返回对象继续只包含 `apiKeyConfigured` 和 `apiKeySource`，不返回密钥。

## 前端交互

每张配置卡底部都有独立操作区：

- `重新读取`：只调用该栏目的 GET 接口并替换该栏目表单。
- `重置为默认`：只将该栏目表单恢复到平台基线，不发请求。
- `保存到 Nacos`：只调用该栏目的 PUT 接口。

大模型卡和政策源卡分别维护 `loading` 与 `saving` 状态。某个栏目保存时，只禁用该栏目的相关按钮，另一个栏目仍可操作。

默认值集中放在单独模块中：

- 大模型默认值为 DeepSeek、`deepseek-v4-pro`、官方 Base URL 和 `json_object`。
- 政策源默认值为应用当前内置的 10 个政策来源。

重置后的配置仅存在于本地表单。保存成功后更新 Zustand 中对应栏目和基线状态，清空 API Key 输入框，显示栏目级成功提示，并刷新模型配置预览。

## 数据流与并发

1. 页面启动仍可通过整份配置接口初始化两个栏目。
2. 栏目重新读取使用独立 GET，不覆盖另一个栏目尚未保存的编辑内容。
3. 栏目保存使用独立 PUT；后端在请求时读取最新 Nacos YAML 并合并，避免前端基于旧快照执行读改写。
4. Nacos 发布成功后返回当前有效栏目配置；发布失败时前端保留用户输入并显示错误。

本次不引入乐观锁。栏目之间的写入节点互不重叠，同一栏目最后一次成功发布生效。

## 校验与错误处理

- 大模型沿用现有 Bean Validation：provider、model、baseUrl、responseFormat 必填，token 为正数。
- 政策源保存前要求名称、类型和 URL 非空，权重范围为 1 到 100。
- 空政策源列表是合法配置，表示显式禁用政策抓取来源。
- Nacos 读取或发布失败返回明确错误，不能静默回退后再覆盖远端配置。
- API Key 留空表示保留，不表示删除；本次不提供删除 Key 操作。

## 测试

后端测试覆盖：

- 保存大模型仅修改 `research.ai.llm`，保留政策源和未知节点。
- 大模型 API Key 留空时保留已有值，填写时更新但响应不泄露明文。
- 保存政策源仅修改 `research.live-data.policy-sources`，保留大模型和未知节点。
- 栏目请求校验失败时不发布 Nacos。

React 测试覆盖：

- 每个栏目都有独立的读取、重置和保存按钮。
- 保存大模型只调用大模型接口，保存政策源只调用政策源接口。
- 重新读取一个栏目不覆盖另一个栏目的未保存内容。
- 重置只影响当前栏目且不会立即调用 API。
- 两个栏目的 loading/saving 状态互不影响。

## 验收标准

- 用户可以分别保存大模型配置和政策源配置。
- 任一栏目保存后，Nacos 中另一个栏目及未知节点保持不变。
- API Key 留空保存后，已配置状态不丢失。
- 两个栏目的读取、重置和保存均可独立完成。
- 后端栏目测试、React 设置页测试和生产构建全部通过。
