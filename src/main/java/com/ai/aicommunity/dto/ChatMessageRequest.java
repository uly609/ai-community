package com.ai.aicommunity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ChatMessageRequest {

    @NotNull(message = "接收用户不能为空")
    private Long receiverUserId;

    @NotBlank(message = "消息内容不能为空")
    @Size(max = 1000, message = "消息不能超过1000个字符")
    private String content;
}
