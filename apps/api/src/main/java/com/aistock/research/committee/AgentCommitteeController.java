package com.aistock.research.committee;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/companies/{symbol}/agent-consensus")
public class AgentCommitteeController {

    private final AgentCommitteeService agentCommitteeService;
    private final AgentCommitteeAiService agentCommitteeAiService;

    public AgentCommitteeController(
            AgentCommitteeService agentCommitteeService,
            AgentCommitteeAiService agentCommitteeAiService
    ) {
        this.agentCommitteeService = agentCommitteeService;
        this.agentCommitteeAiService = agentCommitteeAiService;
    }

    @GetMapping
    public AgentConsensusReport discuss(@PathVariable String symbol) {
        return agentCommitteeService.discuss(symbol);
    }

    @PostMapping("/ai")
    public AgentConsensusReport discussWithAi(@PathVariable String symbol) {
        return agentCommitteeAiService.enhance(agentCommitteeService.discuss(symbol));
    }

    @GetMapping("/prompt")
    public AgentCommitteePromptPreview prompt(@PathVariable String symbol) {
        return agentCommitteeAiService.preview(agentCommitteeService.discuss(symbol));
    }
}
