package com.ai.aicommunity.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CommunityNotificationMessage {

    private Long recipientUserId;
    private Long senderUserId;
    private String type;
    private Long articleId;
    private Long commentId;
    private String content;
}
