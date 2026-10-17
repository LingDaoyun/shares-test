# 短线近端筹码：测试与重新部署记录

用户于 2026-09-24 明确要求“测试一下，然后重新构建部署”，本轮按该授权执行。

## 测试结果

- 后端：`mvn -pl apps/api test`，911 项，0 失败、0 错误、0 跳过。
- 前端：`npm --prefix apps/web-react test`，32 个文件、191 项通过，退出码 0、未处理错误 0。
- 后端专项：123 项通过，覆盖价格区间边界、距离权重、展示桶独立性、有效性/日期门禁、激活模式、历史兼容和实际排序。
- 数据库测试使用专用临时 PostgreSQL 17 容器内的 `chip_test`、`fiveday_test`，没有用业务库运行测试；测试后容器及临时凭据已清理。
- `git diff --check` 通过。

本轮修正了两个本次功能问题：

1. 有效的 0 分扣分被通用显示函数隐藏：只对“近端筹码”保留零值，旧报告 null/undefined 仍不显示虚构贡献。
2. 先扣筹码分、再加其他贡献并封顶可能吞掉罚分：新增回归先复现“99 + 11，罚分 8，错误返回 100”，修正后先封顶再扣分，结果 92。

同时更新了与已选新规则冲突的旧测试断言，并补齐短线页面测试缺少的行情 API mock；没有通过删除安全断言或跳过数据库测试获得通过结果。

## 构建与部署

使用项目既有 `scripts/docker-up.sh`：React TypeScript/Vite 构建、Maven 打包、Docker API/web 镜像构建及容器重建均完成，脚本退出码 0。

工作区没有 `.env.local`，部署时从已有 `ai-stock-api` 容器提取当前连接配置，仅通过子进程环境传递；未把业务密码写入文件或报告。

- API 新容器创建：2026-09-24 09:25:27（北京时间）。
- Web 新容器创建：2026-09-24 09:25:30（北京时间）。
- 两个容器状态均为 `healthy`；API `/actuator/health` 为 `UP`，网页 `/healthz` 为 `ok`。
- 本地构建 JAR 与运行容器内 JAR 的 SHA-256 均为 `fe8bb0fd8777ce560c76d08c6dd7cd7fb6f82ca51edf1e27e9997bb761dc1c72`。
- 网页实际返回 `index-B0aEfn3F.js`、`index-MRz7UN-I.css`，内容哈希均与本地 dist 一致。
- 部署前运行 JAR 中 V1～V5 迁移文件与本地逐项哈希一致，本轮没有数据库迁移增量。
- 原 API/web 镜像保留为 `shares-test-api:before-chip-20260924`、`shares-test-web:before-chip-20260924`。

## 运行检查与边界

- ego-browser 打开 `http://127.0.0.1:5176/#/short-term`，页面和配置正常加载，并保留截图。
- 扫描接口已经返回新策略版本 `short-term-right-side-v5-near-overhead`。
- 初次 100 只股票的小范围请求被全市场覆盖率门槛拦截：92/5912（1.56%），`DATA_BLOCKED`。这是测试请求范围不足，不能作为完整选股成功结果。
- 全市场扫描使用 `scanLimit=6000`、`klineLimit=20`、`limit=3`，任务 `eef7b40a-898f-4c26-b618-edbc30c0303c` 于北京时间 09:34:12 完成，任务状态 `SUCCEEDED`、结果状态 `FINAL_READY`，返回 3 个候选，无阻断原因。
- 原始行情覆盖 5920/5920，剔除无价格记录 404 条后有效行情覆盖 5516/5516；技术复核为预选 109 只中的 20 只，本次请求的 20 只均有足够技术数据，不能把行情覆盖率当成全部预选股的技术复核覆盖率。
- 三个候选均返回“近端筹码压力”解释，但本次线上扫描没有实际扣分：600353、603067 缺少有效本地筹码分布；000504 已有压力估算（参考价 12.76 元、上方 0～5% 筹码占比 10.15%、5～10% 占比 0%、距离加权占比 8.72%），但筹码日期与行情日期不一致。三个 `chipPressureAdjustment` 均为 null，明确标记“未评估”，未把缺失值当作低压力。
- 对同一任务只读复核：600353、603067 的换手率有效覆盖低于 95%，数据质量为 `INSUFFICIENT`；000504 为 `VALID`/`SINGLE_SOURCE`。三者均使用 2026-09-23 的已完成日 K（`COMPLETED_BAR`），行情日为 2026-09-24；现有计算器只有拿到未完成日 K 才会生成盘中估算。因此这些未评估结果符合有效性和日期门槛，本轮未发现新增实现缺陷；没有为了产生扣分而放宽门槛。
- 因此，本轮已验证新版本部署、真实扫描和缺失数据解释路径；真实候选上的实际扣分效果尚未完成线上验证。扣分计算及排序通过了自动化测试，不能以此替代在线有效数据样本。
- 现场看到东方财富连接偶发 SSL EOF，原有抓取器按页续抓；未修改数据源安全门禁或伪造候选。
- 软件测试及部署检查不证明策略收益提升；本轮没有开展收益回测，也未提交或推送代码。

## 本地证据

- `/tmp/shares-chip-20260924-backend-full-final.log`
- `/tmp/shares-chip-20260924-backend-focused-final.log`
- `/tmp/shares-chip-20260924-backend-red.log`
- `/tmp/shares-chip-20260924-frontend-tests.log`
- `/tmp/shares-chip-20260924-deploy/build-deploy.log`
- `/tmp/shares-chip-20260924-deploy/artifact-parity.json`
- `/tmp/shares-chip-20260924-deploy/migration-parity.json`
- `/tmp/shares-chip-20260924-deploy/short-term-page.png`
- `/tmp/shares-chip-20260924-deploy/live-scan-result.json`
