package com.ai.aicommunity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class LearningAssistantRequest {

    private Long conversationId;

    @NotBlank(message = "问题不能为空")
    @Size(max = 500, message = "问题不能超过500个字符")
    private String question;
}
