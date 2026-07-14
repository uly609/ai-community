package com.ai.aicommunity.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class NotificationPushMessage {

    private Long id;
    private Long senderUserId;
    private String type;
    private Long articleId;
    private Long commentId;
    private String content;
    private Integer readStatus;
    private LocalDateTime createTime;
}
