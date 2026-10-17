# 五日观察 v2：环境与主线过滤

用户已批准上一轮方案并要求开发；沿用本会话明确的自测、部署授权，不再重复请求。保留已部署 v1 快照及真实交易记录。

## 决策与范围

- 版本 `five-day-context-v2`；前端默认 v2，可切换 v1 纯量价对照。现有 `/api/five-day` 增加可选 `strategy=v1|v2`，兼容旧客户端默认 v1。数据库原表按规则版本隔离，新增上下文存入 snapshot_json，不改旧快照。
- v1 的横盘 8%/首尾 3%、换手 1.2、资金 3/5、流动性、入场上限、失效位、五日计划及成本假设保持原值。v2 增加环境/行业/转强/公告条件，不为农业或当天任何行业人工加分。无合格股是合法结果。
- 市场使用支持范围内同一信号日及上一交易日的本地日线；覆盖分母保留技术基线评估前的supportedCount，不能仅按成功样本自算，最少 200 只、覆盖至少 80%。上涨广度不足35%且收益中位数为负，或单日跌幅至少7%的样本占比达到8%，为 RISK_OFF；广度至少50%且中位数非负为 NORMAL；其余 CAUTION；覆盖不够 UNKNOWN。RISK_OFF/UNKNOWN 不允许升级为执行条件齐备；CAUTION 只观察。
- 板块使用当前选择的主板/创业板/科创板范围及冻结基线行业成员，每行业至少5只、完整25日覆盖至少80%。分别计算个股3/5日收益再取行业等权平均，与本范围同覆盖样本的等权基准比较；5日涨幅为正、3/5日超额均正、5日上涨家数占比至少55%，且5日平均成交量/此前20日均量>=1.1的成员占比至少50%，并在合格数据行业5日收益排名前25%时为STRONG。其余WEAK，数据不足UNKNOWN。量是同股比值后统计参与比例，不跨股票加原始股数，也不冒充成交额或换手率。所有阈值是待验证研究初值。
- 先过滤 STRONG 行业，再复核个股形态；100只限额在强行业间轮询分配，行业内优先已转强股，再按3日收益、振幅及代码确定顺序。v2 coverage.priceQualifiedCount 指强行业内价格预选，单独披露全部形态合格数与行业过滤数，未复核仅指符合主线预选但因容量未检查者。
- 转强定义：信号收盘高于前收盘、收盘>=近5日均价、信号日收盘位于当日振幅上部至少60%。平价日 high=low 不作转强。全部用信号日已完成日线，不能借次日数据做当日成交推断。
- 政策读取现有配置的政策源，最近7个自然日；原文链接及公开日期保留。只输出与细分行业关键词直接关联的政策线索，不证明公司主营受益，不把政策来源失败当无利空，不按标题加收益分数。旧政策、未来日期、非法URL及重复标题/链接不得作为新催化；日期未知单列。
- 公司消息先接巨潮最近公告（最多30条、最近7日），按股票代码校验身份。减持、立案、处罚、诉讼、停牌、风险提示等标题只形成需阅读原文的风险线索，不宣称已证实利空；公告源不可用/日期无法确认/最新30条不足覆盖窗口为UNKNOWN。原文及信息获取时间留存。该首版消息覆盖限于公开政策及公司公告，不声称覆盖全网新闻或传闻。
- 候选判断：NORMAL+STRONG+转强+完整且合格资金+公告NO_TITLE_RISK为ELIGIBLE（条件齐备，仍非下单指令）；风险线索为AVOID/待核实，数据未知UNKNOWN，其他WATCH。政策非强制利好门槛；没有证实催化不会被伪造为有。
- 入场前检查独立于冻结信号，不能覆盖历史；至少核对计划入场日、09:30–11:30或13:00至14:57之前连续竞价、非前瞻资格、个股当前报价日期和时点、开盘/现价边界、公告风险，并明确实时全市场环境是否已核实。实际账本只录已发生交易，不连接券商。
- 每次扫描冻结 Context.assessedAt，日线和行业的信号日期独立显示。现在取得的消息只说明现在的判断；过下一入场日09:15仍为非前瞻。v1首条及v2首条分别不可覆盖；局部数据缺口明确披露，空候选且关键数据未知不固化为完整成功。
- v2历史只读按版本过滤；对照情景是强行业内的量价候选，执行组是其ELIGIBLE子集，重叠样本不作因果证明。对照情景可能包含不可执行观察，必须与真实交易和执行组分开显示。五日持有从实际首次买入日计算。

## 共享 Java 合约

包 `com.aistock.research.fiveday`，所有下列records定义于FiveDayContextModels。BigDecimal用于百分数（如80代表80%），Instant用于采集时间。

```java
record StockBars(String symbol,String name,String industry,List<EastMoneyKLine> bars) {}
record Market(String state,int expectedCount,int coveredCount,BigDecimal coveragePercent,BigDecimal advancingPercent,BigDecimal medianChangePercent,BigDecimal severeDeclinePercent,List<String> reasons) {}
record Sector(String industry,String state,int rank,int memberCount,int coveredCount,BigDecimal coveragePercent,BigDecimal return3Percent,BigDecimal return5Percent,BigDecimal excess3Percent,BigDecimal excess5Percent,BigDecimal advancing5Percent,BigDecimal volumeExpansionPercent,List<String> symbols,List<String> reasons) {}
record Turning(boolean confirmed,BigDecimal closeVsMa5Percent,BigDecimal closePositionPercent,List<String> reasons) {}
record Evidence(String kind,String title,String url,String source,String publishedAt,Instant observedAt,String interpretation,String temporalStatus) {}
record PolicyBundle(String status,List<Evidence> evidence,List<String> notes) {}
record EventReview(String status,List<Evidence> evidence,List<String> notes) {}
record CandidateAssessment(String symbol,String industry,String decision,Turning turning,EventReview events,List<Evidence> policies,List<String> reasons) {}
record Context(Instant assessedAt,Market market,List<Sector> sectors,List<CandidateAssessment> assessments,List<Evidence> policies,String policyStatus,List<String> notes) {}
```

FiveDayContextEngine(TradingClockService): Context analyze(LocalDate,Instant,List<StockBars> marketStocks,List<StockBars> scopeStocks); Turning turning(String,List<EastMoneyKLine>,LocalDate); BigDecimal returnPercent(String,List<EastMoneyKLine>,LocalDate,int days)。引擎纯计算，无供应商或数据库请求。

FiveDayEventEvidenceService(GovPolicyClient,CninfoClient): PolicyBundle fetchPolicies(Instant asOf); EventReview review(String symbol,String name,String industry,Instant asOf); List<Evidence> relatedPolicies(String industry,PolicyBundle policies)。asOf为真实扫描开始/获取时点，不可伪装历史请求。所有状态/空集合明确，纯日期保留精度与首次观察时间。

Snapshot新增nullable Context context，保留旧构造重载，旧JSON可读。HistoryRow新增nullable String decision，保留旧构造重载。

## 验收

以真实可重复测试覆盖：弱市场和覆盖不足不通过；板块3/5持续性、广度、放量参与及样本数；热板块不过热股名额不再被冷板块挤占；只横盘未转强不执行；未来/旧/重复/错股票政策公告不晋级；v1/v2冻结及统计隔离；新页面版本切换和数据缺失；真实扫描、接口边界、迁移兼容及重启恢复。所有DB测试仅独立临时PostgreSQL。只部署本机api/web，备份业务库及旧镜像，不改其他服务。
