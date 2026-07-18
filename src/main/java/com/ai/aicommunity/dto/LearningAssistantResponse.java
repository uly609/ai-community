package com.ai.aicommunity.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

@Data
@AllArgsConstructor
public class LearningAssistantResponse {

    private String answer;

    private Boolean aiEnabled;

    private Long conversationId;

    private List<Reference> references;

    @Data
    @AllArgsConstructor
    public static class Reference {

        private String type;

        private Long id;

        private String title;
    }
}
