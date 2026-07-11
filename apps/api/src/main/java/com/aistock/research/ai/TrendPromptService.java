package com.aistock.research.ai;

import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class TrendPromptService {

    private static final String PROMPT_NAME = "policy-industry-hidden-trend-analysis";
    private static final String PROMPT_VERSION = "v1.1.0";

    public TrendPromptPreview preview(TrendPromptRequest request) {
        return new TrendPromptPreview(
                PROMPT_NAME,
                PROMPT_VERSION,
                modelInstruction(),
                userPrompt(request),
                outputSchema(),
                qualityChecklist(),
                guardrails()
        );
    }

    public Map<String, Object> structuredOutputSchema() {
        return objectSchema(
                properties(
                        entry("document_fingerprint", objectSchema(
                                properties(
                                        entry("policy_level", stringSchema()),
                                        entry("time_horizon", stringSchema()),
                                        entry("core_subjects", arraySchema(stringSchema())),
                                        entry("policy_or_business_tools", arraySchema(stringSchema()))
                                ),
                                List.of("policy_level", "time_horizon", "core_subjects", "policy_or_business_tools")
                        )),
                        entry("explicit_signals", arraySchema(objectSchema(
                                properties(
                                        entry("signal", stringSchema()),
                                        entry("evidence_excerpt", stringSchema()),
                                        entry("source_type", stringSchema()),
                                        entry("confidence", numberSchema())
                                ),
                                List.of("signal", "evidence_excerpt", "source_type", "confidence")
                        ))),
                        entry("agent_cross_checks", arraySchema(objectSchema(
                                properties(
                                        entry("agent_role", stringSchema()),
                                        entry("key_claim", stringSchema()),
                                        entry("evidence_refs", arraySchema(stringSchema())),
                                        entry("verdict", stringSchema()),
                                        entry("confidence", numberSchema()),
                                        entry("concerns", arraySchema(stringSchema()))
                                ),
                                List.of("agent_role", "key_claim", "evidence_refs", "verdict", "confidence", "concerns")
                        ))),
                        entry("hidden_trends", arraySchema(objectSchema(
                                properties(
                                        entry("trend_name", stringSchema()),
                                        entry("trend_type", stringSchema()),
                                        entry("logic_chain", arraySchema(stringSchema())),
                                        entry("time_windows", objectSchema(
                                                properties(
                                                        entry("0_1_year", stringSchema()),
                                                        entry("1_3_year", stringSchema()),
                                                        entry("3_5_year", stringSchema()),
                                                        entry("5_10_year", stringSchema())
                                                ),
                                                List.of("0_1_year", "1_3_year", "3_5_year", "5_10_year")
                                        )),
                                        entry("beneficiary_profiles", arraySchema(stringSchema())),
                                        entry("risk_or_loser_segments", arraySchema(stringSchema())),
                                        entry("trend_strength", numberSchema()),
                                        entry("evidence_strength", numberSchema())
                                ),
                                List.of("trend_name", "trend_type", "logic_chain", "time_windows",
                                        "beneficiary_profiles", "risk_or_loser_segments", "trend_strength", "evidence_strength")
                        ))),
                        entry("industry_chain_map", arraySchema(objectSchema(
                                properties(
                                        entry("chain_segment", stringSchema()),
                                        entry("key_capabilities", arraySchema(stringSchema())),
                                        entry("a_share_screening_factors", arraySchema(stringSchema()))
                                ),
                                List.of("chain_segment", "key_capabilities", "a_share_screening_factors")
                        ))),
                        entry("company_research_tasks", arraySchema(objectSchema(
                                properties(
                                        entry("task", stringSchema()),
                                        entry("why_it_matters", stringSchema()),
                                        entry("priority", stringSchema())
                                ),
                                List.of("task", "why_it_matters", "priority")
                        ))),
                        entry("monitoring_indicators", arraySchema(objectSchema(
                                properties(
                                        entry("indicator", stringSchema()),
                                        entry("direction_to_watch", stringSchema()),
                                        entry("data_source_hint", stringSchema())
                                ),
                                List.of("indicator", "direction_to_watch", "data_source_hint")
                        ))),
                        entry("counter_evidence", arraySchema(objectSchema(
                                properties(
                                        entry("condition", stringSchema()),
                                        entry("impact", stringSchema()),
                                        entry("severity", stringSchema())
                                ),
                                List.of("condition", "impact", "severity")
                        ))),
                        entry("evidence_gaps", arraySchema(stringSchema())),
                        entry("overall_assessment", objectSchema(
                                properties(
                                        entry("summary", stringSchema()),
                                        entry("confidence", numberSchema()),
                                        entry("next_action", stringSchema())
                                ),
                                List.of("summary", "confidence", "next_action")
                        ))
                ),
                List.of("document_fingerprint", "explicit_signals", "agent_cross_checks", "hidden_trends",
                        "industry_chain_map", "company_research_tasks", "monitoring_indicators", "counter_evidence",
                        "evidence_gaps", "overall_assessment")
        );
    }

    private String modelInstruction() {
        return """
                你是一个面向中国 A 股长线投资的政策与产业研究分析师。你的任务不是荐股，也不是复述文件，
                而是从政府规划、产业报告、新闻和公告中识别未来 1-10 年可能形成真实产业增量的趋势。

                分析原则：
                1. 证据优先：所有判断必须绑定原文证据或明确标注为推断。
                2. 区分层级：把“文件明确写到的内容”和“基于机制推导出的潜在趋势”分开。
                3. 长线视角：优先关注政策工具、财政/采购/监管/标准/技术路线、产业链瓶颈和商业化节奏。
                4. 反证意识：必须列出可能使趋势失效的条件、被市场过度定价的风险、以及需要继续验证的数据。
                5. 可落地：输出必须能被规则引擎、公司池筛选和后续公告解析使用。
                6. 克制表达：不要使用“确定受益”“必然上涨”等投资结论。不要生成买入、卖出、仓位建议。
                7. 多 Agent 交叉验证：最终 JSON 必须包含不同研究角色的结论摘要，角色至少覆盖：
                   政策原文核验、产业链机制推导、财政/统计/订单验证、风险反证、A股映射、裁判汇总。
                8. 私下逐步思考，最终只输出结构化结论，不暴露隐藏推理过程。

                输出要求：
                - 只输出 JSON，不要 Markdown。
                - JSON 字段必须符合调用方提供的 schema。
                - 每条证据摘录不超过 80 个中文字符。
                - explicit_signals 输出 3-6 条；hidden_trends 输出 2-4 条；industry_chain_map 输出 4-6 条。
                - agent_cross_checks 输出 4-6 条，只写各角色的结论、证据引用和疑点，不写隐藏推理过程。
                - company_research_tasks 输出 3-6 条；monitoring_indicators 输出 3-8 条；counter_evidence 输出 2-5 条。
                - 如果证据不足，明确写入 evidence_gaps，不要编造。
                """;
    }

    private String userPrompt(TrendPromptRequest request) {
        return """
                请分析下面这份材料，找出它蕴含的中长期产业趋势，并把结果输出为 JSON。

                文档元信息：
                - 标题：%s
                - 类型：%s
                - 发布机构：%s
                - 发布时间：%s
                - 来源链接：%s
                - 关注主题：%s
                - 已知公司池：%s

                分析任务：
                1. 做文档指纹识别：判断材料的政策层级、时间跨度、关键产业对象、政策工具或商业驱动。
                2. 抽取显性信号：列出文件明确提到的方向、工程、约束、资金、监管、标准、技术路线。
                3. 推导隐含趋势：基于“政策工具 -> 产业机制 -> 需求变化 -> 供给瓶颈 -> 公司能力”的链条，找出未来可能被市场逐步验证的趋势。
                4. 做多 Agent 交叉验证：分别用政策原文核验、产业链机制、财政/统计/订单验证、风险反证、A股映射、裁判汇总六个角色检查趋势假设。
                5. 拆产业链：把趋势拆成上游、中游、下游、基础设施、软件/数据/服务、替代品或潜在受损环节。
                6. 映射 A 股筛选条件：不要直接荐股；请输出公司筛选画像、核心资产特征、需要读取的公告/年报字段。
                7. 设计验证指标：给出 3-8 个后续可监控指标，例如政策预算、招投标、产能利用率、价格、订单、研发资本化、毛利率、现金流。
                8. 输出反证：列出什么情况出现时，应降低趋势置信度。
                9. 输出时间窗口：分 0-1 年、1-3 年、3-5 年、5-10 年判断落地节奏。

                材料正文节选：
                %s
                """.formatted(
                request.documentTitle(),
                request.documentType(),
                blankToUnknown(request.sourceOrganization()),
                blankToUnknown(request.publishedAt()),
                blankToUnknown(request.sourceUrl()),
                listText(request.focusThemes()),
                listText(request.knownCompanies()),
                request.contentExcerpt()
        );
    }

    private Map<String, Object> outputSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("document_fingerprint", Map.of(
                "policy_level", "国家级/部委级/地方级/行业报告/公司公告/新闻",
                "time_horizon", "0-1年/1-3年/3-5年/5-10年",
                "core_subjects", List.of("产业对象"),
                "policy_or_business_tools", List.of("财政支持/监管约束/标准制定/示范工程/采购/技术路线/需求侧变化")
        ));
        schema.put("explicit_signals", List.of(Map.of(
                "signal", "文件明确表达的信号",
                "evidence_excerpt", "不超过80个中文字符",
                "source_type", "原文/表格/标题/机构表述",
                "confidence", "0-100"
        )));
        schema.put("agent_cross_checks", List.of(Map.of(
                "agent_role", "政策原文核验/产业链机制推导/财政统计验证/风险反证/A股映射/裁判汇总",
                "key_claim", "该角色确认或质疑的核心判断",
                "evidence_refs", List.of("对应 explicit_signals 或原文摘录"),
                "verdict", "SUPPORTED/PARTIAL/WEAK/CONFLICTED",
                "confidence", "0-100",
                "concerns", List.of("该角色仍然担心的问题")
        )));
        schema.put("hidden_trends", List.of(Map.of(
                "trend_name", "趋势名称",
                "trend_type", "需求扩张/供给替代/国产替代/效率提升/监管强化/基础设施建设/商业模式变化",
                "logic_chain", List.of("政策工具", "产业机制", "需求或供给变化", "可能形成的公司能力"),
                "time_windows", Map.of("0_1_year", "观察点", "1_3_year", "验证点", "3_5_year", "兑现点", "5_10_year", "长期形态"),
                "beneficiary_profiles", List.of("公司筛选画像"),
                "risk_or_loser_segments", List.of("可能受损或被替代环节"),
                "trend_strength", "0-100",
                "evidence_strength", "0-100"
        )));
        schema.put("industry_chain_map", List.of(Map.of(
                "chain_segment", "上游/中游/下游/基础设施/软件数据服务/渠道",
                "key_capabilities", List.of("核心能力"),
                "a_share_screening_factors", List.of("可用于筛选公司的字段或因子")
        )));
        schema.put("company_research_tasks", List.of(Map.of(
                "task", "后续要读取的公告、年报或财务字段",
                "why_it_matters", "它验证哪个趋势假设",
                "priority", "HIGH/MEDIUM/LOW"
        )));
        schema.put("monitoring_indicators", List.of(Map.of(
                "indicator", "监控指标",
                "direction_to_watch", "上升/下降/突破阈值/结构变化",
                "data_source_hint", "政府公告/招投标/年报/交易所公告/行业数据/行情数据"
        )));
        schema.put("counter_evidence", List.of(Map.of(
                "condition", "反证条件",
                "impact", "降低置信度/降低估值容忍度/移出观察池",
                "severity", "HIGH/MEDIUM/LOW"
        )));
        schema.put("evidence_gaps", List.of("仍缺哪些资料才能提高置信度"));
        schema.put("overall_assessment", Map.of(
                "summary", "不超过200字的趋势判断",
                "confidence", "0-100",
                "next_action", "进入公司池筛选/继续收集证据/暂不纳入主题"
        ));
        return schema;
    }

    private List<String> qualityChecklist() {
        return List.of(
                "是否区分了原文事实和模型推断",
                "是否给出了多 Agent 交叉验证结论，并列出每个角色的疑点",
                "每个隐含趋势是否具备政策工具、产业机制和验证指标",
                "是否列出了会推翻趋势的反证条件",
                "是否避免直接给出买卖建议",
                "是否能转成规则引擎因子或公司公告抓取任务",
                "是否指出证据缺口，而不是用空泛判断填补"
        );
    }

    private List<String> guardrails() {
        return List.of(
                "不得编造文件中不存在的政策、预算、公司订单或财务数据",
                "不得输出确定性投资收益承诺",
                "不得把短期市场热度当作长期趋势证据",
                "公司名称只能作为研究候选，不得作为买入建议",
                "证据不足时必须降低 confidence 并写入 evidence_gaps"
        );
    }

    private String blankToUnknown(String value) {
        if (value == null || value.isBlank()) {
            return "未知";
        }
        return value;
    }

    private String listText(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "未指定";
        }
        return String.join("、", values);
    }

    private Map<String, Object> objectSchema(Map<String, Object> properties, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", required);
        schema.put("additionalProperties", false);
        return schema;
    }

    private Map<String, Object> arraySchema(Map<String, Object> items) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "array");
        schema.put("items", items);
        return schema;
    }

    private Map<String, Object> stringSchema() {
        return Map.of("type", "string");
    }

    private Map<String, Object> numberSchema() {
        return Map.of("type", "number", "minimum", 0, "maximum", 100);
    }

    @SafeVarargs
    private Map<String, Object> properties(Map.Entry<String, Object>... entries) {
        Map<String, Object> properties = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : entries) {
            properties.put(entry.getKey(), entry.getValue());
        }
        return properties;
    }

    private Map.Entry<String, Object> entry(String key, Object value) {
        return Map.entry(key, value);
    }
}
