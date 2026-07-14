package com.ai.aicommunity.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("user_notification")
public class UserNotification {

    private Long id;
    private Long recipientUserId;
    private Long senderUserId;
    private String type;
    private Long articleId;
    private Long commentId;
    private String content;
    private Integer readStatus;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
