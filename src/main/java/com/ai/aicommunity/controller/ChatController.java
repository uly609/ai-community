package com.ai.aicommunity.controller;

import com.ai.aicommunity.common.Result;
import com.ai.aicommunity.dto.ChatMessageRequest;
import com.ai.aicommunity.entity.ChatMessage;
import com.ai.aicommunity.service.ChatService;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/chats")
public class ChatController {

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    @PostMapping("/messages")
    public Result<ChatMessage> send(@Valid @RequestBody ChatMessageRequest request) {
        return Result.success(chatService.send(request.getReceiverUserId(), request.getContent()));
    }

    @GetMapping("/conversations/{otherUserId}")
    public Result<Page<ChatMessage>> conversation(@PathVariable Long otherUserId,
                                                  @RequestParam(defaultValue = "1") Integer current,
                                                  @RequestParam(defaultValue = "20") Integer size) {
        return Result.success(chatService.conversation(otherUserId, current, size));
    }

    @PutMapping("/conversations/{otherUserId}/read")
    public Result<String> markRead(@PathVariable Long otherUserId) {
        chatService.markRead(otherUserId);
        return Result.success("已读");
    }
}
