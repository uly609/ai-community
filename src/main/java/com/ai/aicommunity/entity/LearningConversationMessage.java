package com.ai.aicommunity.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("learning_conversation_message")
public class LearningConversationMessage {

    private Long id;
    private Long conversationId;
    private Long userId;
    private String role;
    private String content;
    private LocalDateTime createTime;
}
