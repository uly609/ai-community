package com.ai.aicommunity.controller;

import com.ai.aicommunity.common.Result;
import com.ai.aicommunity.dto.LearningAssistantRequest;
import com.ai.aicommunity.dto.LearningAssistantResponse;
import com.ai.aicommunity.entity.LearningConversation;
import com.ai.aicommunity.entity.LearningConversationMessage;
import com.ai.aicommunity.service.LearningAssistantService;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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
        return Result.success(learningAssistantService.ask(request.getConversationId(), request.getQuestion()));
    }

    @GetMapping("/conversations")
    public Result<Page<LearningConversation>> conversations(
            @RequestParam(defaultValue = "1") Integer current,
            @RequestParam(defaultValue = "10") Integer size) {
        return Result.success(learningAssistantService.myConversations(current, size));
    }

    @GetMapping("/conversations/{conversationId}/messages")
    public Result<Page<LearningConversationMessage>> messages(
            @PathVariable Long conversationId,
            @RequestParam(defaultValue = "1") Integer current,
            @RequestParam(defaultValue = "20") Integer size) {
        return Result.success(learningAssistantService.messages(conversationId, current, size));
    }
}
