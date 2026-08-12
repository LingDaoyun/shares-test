package com.aistock.research.configuration;

import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class RuntimeConfigDefaults {

    public StoredLlmConfig llm() {
        return new StoredLlmConfig(
                "deepseek",
                null,
                "DEEPSEEK_API_KEY",
                "deepseek-v4-pro",
                "https://api.deepseek.com",
                "json_object",
                false,
                null,
                8192,
                null
        );
    }

    public List<PolicySourceConfig> policySources() {
        return List.of(
                source("中国政府网", "json", "https://www.gov.cn/zhengce/zuixin/ZUIXINZHENGCE.json", 100),
                source("国家发展改革委", "html", "https://www.ndrc.gov.cn/xxgk/zcfb/ghwb/", 92),
                source("工业和信息化部", "html", "https://www.miit.gov.cn/zwgk/zcwj/", 90),
                source("科学技术部", "html", "https://www.most.gov.cn/xxgk/xinxifenlei/fdzdgknr/fgzc/gfxwj/", 86),
                source("财政部", "html", "https://www.mof.gov.cn/zhengwuxinxi/caizhengxinwen/", 82),
                source("国家能源局", "html", "https://www.nea.gov.cn/zcfb/", 84),
                source("中国证监会", "html", "https://www.csrc.gov.cn/csrc/c100028/zfxxgk_zdgk.shtml", 80),
                source("生态环境部", "html", "https://www.mee.gov.cn/xxgk/", 84),
                source("农业农村部", "html", "https://www.moa.gov.cn/govpublic/", 82),
                source("交通运输部", "html", "https://xxgk.mot.gov.cn/zhengceapp/740/833/list_7234.html", 82)
        );
    }

    private PolicySourceConfig source(String name, String type, String url, int weight) {
        return new PolicySourceConfig(name, type, url, weight);
    }
}
