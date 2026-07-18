package com.ai.aicommunity.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("chat_message")
public class ChatMessage {

    private Long id;
    private Long senderUserId;
    private Long receiverUserId;
    private String content;
    private Integer readStatus;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
