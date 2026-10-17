# 五日观察 v2 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use subagent-driven-development to implement this plan task-by-task. The user already approved this design and execution.

**Goal:** 将市场环境、持续强板块、转强及政策公告证据纳入五日观察，保留v1对照。

**Architecture:** 复用既有扫描、日线、资金、持有期及five_day_scan存储；新增纯上下文引擎和证据服务，通过版本参数隔离。前端扩展现有页，避免另造交易系统。

**Tech Stack:** Java17 / Spring Boot3.3 / PostgreSQL17 / React / TypeScript / Vitest。

## Global Constraints

详细阈值、共享类型和接口以同日spec为准。TDD；不并行运行Maven以避免共享target竞争。所有DB测试只连临时库，同时设置AISTOCK_TEST与JDBC_DATABASE两套变量。不得改写v1快照。仅本机api/web可部署，不下单、不写模拟成交、不commit/push。

### Task 1: 市场与板块引擎
- [x] 新建FiveDayContextModels、FiveDayContextEngine及FiveDayContextEngineTest，先验证红灯再实现。
- [x] 覆盖单日行情不能代替3/5日主线、弱市/缺覆盖、行业成员少/缺口、参与度、同股量比、次日数据隔离和转强条件。
- [x] 聚焦命令 `mvn -pl apps/api -Dtest=FiveDayContextEngineTest test` 通过后交接。

### Task 2: 可追溯政策公告证据
- [x] 新建FiveDayEventEvidenceService及对应单元测试，先用旧日期、重复链接、未来公告、错误证券和供应商失败复现不安全晋级。
- [x] 按共享合约实现最近7日政策线索及最近30条公告的限量审阅，未知与风险线索独立，不自动断言受益。
- [x] 聚焦命令 `mvn -pl apps/api -Dtest=FiveDayEventEvidenceServiceTest test` 经根代理协调执行。

### Task 3: 扫描、历史及执行集成
- [x] FiveDayModels添加兼容nullablecontext/decision；FiveDayService引入v2版本Key、上下文阶段、按强行业轮询预选和逐股评估；原v1方法保持兼容。
- [x] FiveDayController增加strategy参数；FiveDayHistoryService接受显式版本并按新执行条件组统计；冻结不覆盖测试先行。
- [x] 入场前检查与冻结历史分离，新增控制器回归；FiveDayScheduler分别扫描/更新v1及v2。
- [x] 新服务测试验证v1行为保持、强行业顺序、未知不固化、实际账本限制及版本隔离。

### Task 4: 页面与解释
- [x] FiveDayScope增加可选strategy；默认v2、显式v1对照；版本/范围切换正确清理旧请求。
- [x] 新上下文面板展示市场、行业排序、指标/缺口和政策；候选默认显示执行条件齐备组，可展开观察与对照；详情解释每个门槛和原文。
- [x] 历史按版本查询，明确对照情景可能不可执行。执行检查按钮不写交易。
- [x] Vitest交互测试先行，运行前端全量与tsc/Vite构建。

### Task 5: 验证、部署及记录
- [x] 独立PostgreSQL全量后端测试及package；新版本真实扫描与v1快照不变验证；保留失效/缺失状态，不为生成股票调整阈值。
- [x] 业务库/旧镜像备份，保留运行配置，docker compose只更新api/web；确认健康、产物哈希、页面和重启恢复。
- [x] 更新README及v2验证记录，清理临时测试库和浏览器空间，交付结果与覆盖限制。
