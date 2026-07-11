package com.aistock.research.policy;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/policy/themes")
public class PolicyThemeController {

    private final PolicyThemeService themeService;

    public PolicyThemeController(PolicyThemeService themeService) {
        this.themeService = themeService;
    }

    @GetMapping
    public List<PolicyTheme> listThemes() {
        return themeService.listThemes();
    }
}

