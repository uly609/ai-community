package com.ai.aicommunity.service;

import com.ai.aicommunity.dto.ChatPushMessage;
import com.ai.aicommunity.entity.ChatMessage;
import com.ai.aicommunity.exception.BusinessException;
import com.ai.aicommunity.mapper.ChatMessageMapper;
import com.ai.aicommunity.utils.UserHolder;
import com.ai.aicommunity.websocket.ChatWebSocketHandler;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
public class ChatService {

    private final ChatMessageMapper chatMessageMapper;
    private final ChatWebSocketHandler chatWebSocketHandler;

    public ChatService(ChatMessageMapper chatMessageMapper, ChatWebSocketHandler chatWebSocketHandler) {
        this.chatMessageMapper = chatMessageMapper;
        this.chatWebSocketHandler = chatWebSocketHandler;
    }

    public ChatMessage send(Long receiverUserId, String content) {
        Long senderUserId = requireUser();
        if (senderUserId.equals(receiverUserId)) {
            throw new BusinessException("不能给自己发送私信");
        }

        ChatMessage message = new ChatMessage();
        message.setSenderUserId(senderUserId);
        message.setReceiverUserId(receiverUserId);
        message.setContent(content);
        message.setReadStatus(0);
        message.setCreateTime(LocalDateTime.now());
        message.setUpdateTime(LocalDateTime.now());
        chatMessageMapper.insert(message);

        ChatPushMessage pushMessage = new ChatPushMessage(
                message.getId(),
                message.getSenderUserId(),
                message.getReceiverUserId(),
                message.getContent(),
                message.getCreateTime()
        );
        chatWebSocketHandler.push(receiverUserId, pushMessage);
        chatWebSocketHandler.push(senderUserId, pushMessage);
        return message;
    }

    public Page<ChatMessage> conversation(Long otherUserId, Integer current, Integer size) {
        Long userId = requireUser();
        int pageNo = current == null || current < 1 ? 1 : current;
        int pageSize = size == null || size < 1 ? 20 : Math.min(size, 50);
        return chatMessageMapper.selectPage(new Page<>(pageNo, pageSize),
                new LambdaQueryWrapper<ChatMessage>()
                        .and(query -> query
                                .eq(ChatMessage::getSenderUserId, userId)
                                .eq(ChatMessage::getReceiverUserId, otherUserId))
                        .or(query -> query
                                .eq(ChatMessage::getSenderUserId, otherUserId)
                                .eq(ChatMessage::getReceiverUserId, userId))
                        .orderByDesc(ChatMessage::getCreateTime));
    }

    public void markRead(Long otherUserId) {
        Long userId = requireUser();
        chatMessageMapper.update(null, new LambdaUpdateWrapper<ChatMessage>()
                .eq(ChatMessage::getSenderUserId, otherUserId)
                .eq(ChatMessage::getReceiverUserId, userId)
                .eq(ChatMessage::getReadStatus, 0)
                .set(ChatMessage::getReadStatus, 1)
                .set(ChatMessage::getUpdateTime, LocalDateTime.now()));
    }

    private Long requireUser() {
        Long userId = UserHolder.getUserId();
        if (userId == null) {
            throw new BusinessException("用户未登录");
        }
        return userId;
    }
}
