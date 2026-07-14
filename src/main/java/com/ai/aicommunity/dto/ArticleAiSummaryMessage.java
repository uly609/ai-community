package com.ai.aicommunity.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ArticleAiSummaryMessage {

    private Long articleId;
    private String title;
    private String content;
}
