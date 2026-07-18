package com.ai.aicommunity.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@AllArgsConstructor
public class ChatPushMessage {

    private Long id;
    private Long senderUserId;
    private Long receiverUserId;
    private String content;
    private LocalDateTime createTime;
}
