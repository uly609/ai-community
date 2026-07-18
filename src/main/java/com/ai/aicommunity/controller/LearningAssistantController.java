package com.ai.aicommunity.controller;

import com.ai.aicommunity.common.Result;
import com.ai.aicommunity.dto.LearningAssistantRequest;
import com.ai.aicommunity.dto.LearningAssistantResponse;
import com.ai.aicommunity.service.LearningAssistantService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/learning-assistant")
public class LearningAssistantController {

    private final LearningAssistantService learningAssistantService;

    public LearningAssistantController(LearningAssistantService learningAssistantService) {
        this.learningAssistantService = learningAssistantService;
    }

    @PostMapping("/ask")
    public Result<LearningAssistantResponse> ask(@Valid @RequestBody LearningAssistantRequest request) {
        return Result.success(learningAssistantService.ask(request.getQuestion()));
    }
}
