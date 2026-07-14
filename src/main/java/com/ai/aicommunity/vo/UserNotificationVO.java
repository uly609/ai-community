package com.ai.aicommunity.vo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class UserNotificationVO {

    private Long id;
    private Long senderUserId;
    private String senderNickname;
    private String type;
    private Long articleId;
    private Long commentId;
    private String content;
    private Integer readStatus;
    private LocalDateTime createTime;
}
