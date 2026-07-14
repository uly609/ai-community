package com.ai.aicommunity.service;

import com.ai.aicommunity.dto.CommunityNotificationMessage;
import com.ai.aicommunity.dto.NotificationPushMessage;
import com.ai.aicommunity.entity.User;
import com.ai.aicommunity.entity.UserNotification;
import com.ai.aicommunity.exception.BusinessException;
import com.ai.aicommunity.mapper.UserNotificationMapper;
import com.ai.aicommunity.mapper.UserMapper;
import com.ai.aicommunity.utils.UserHolder;
import com.ai.aicommunity.vo.UserNotificationVO;
import com.ai.aicommunity.websocket.NotificationWebSocketHandler;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class NotificationService {

    private final UserNotificationMapper userNotificationMapper;
    private final UserMapper userMapper;
    private final NotificationWebSocketHandler notificationWebSocketHandler;

    public NotificationService(UserNotificationMapper userNotificationMapper, UserMapper userMapper,
                               NotificationWebSocketHandler notificationWebSocketHandler) {
        this.userNotificationMapper = userNotificationMapper;
        this.userMapper = userMapper;
        this.notificationWebSocketHandler = notificationWebSocketHandler;
    }

    @Transactional(rollbackFor = Exception.class)
    public void createFromMessage(CommunityNotificationMessage message) {
        if (message.getRecipientUserId().equals(message.getSenderUserId())) {
            return;
        }
        UserNotification notification = new UserNotification();
        notification.setRecipientUserId(message.getRecipientUserId());
        notification.setSenderUserId(message.getSenderUserId());
        notification.setType(message.getType());
        notification.setArticleId(message.getArticleId());
        notification.setCommentId(message.getCommentId());
        notification.setContent(message.getContent());
        notification.setReadStatus(0);
        notification.setCreateTime(LocalDateTime.now());
        notification.setUpdateTime(LocalDateTime.now());
        try {
            int inserted = userNotificationMapper.insert(notification);
            if (inserted == 1) {
                pushAfterTransactionCommit(notification);
            }
        } catch (DuplicateKeyException ignored) {
            // RocketMQ 至少投递一次，重复消息由数据库唯一索引幂等处理。
        }
    }

    public Page<UserNotificationVO> myNotifications(Integer current, Integer size) {
        Long userId = requireUserId();
        int pageNo = current == null || current < 1 ? 1 : current;
        int pageSize = size == null || size < 1 ? 10 : Math.min(size, 50);
        Page<UserNotification> notificationPage = userNotificationMapper.selectPage(new Page<>(pageNo, pageSize),
                new LambdaQueryWrapper<UserNotification>()
                        .eq(UserNotification::getRecipientUserId, userId)
                        .orderByAsc(UserNotification::getReadStatus)
                        .orderByDesc(UserNotification::getCreateTime));
        return toNotificationVOPage(notificationPage);
    }

    public void markRead(Long notificationId) {
        Long userId = requireUserId();
        userNotificationMapper.update(null, new LambdaUpdateWrapper<UserNotification>()
                .eq(UserNotification::getId, notificationId)
                .eq(UserNotification::getRecipientUserId, userId)
                .eq(UserNotification::getReadStatus, 0)
                .set(UserNotification::getReadStatus, 1)
                .set(UserNotification::getUpdateTime, LocalDateTime.now()));
    }

    public void markAllRead() {
        Long userId = requireUserId();
        userNotificationMapper.update(null, new LambdaUpdateWrapper<UserNotification>()
                .eq(UserNotification::getRecipientUserId, userId)
                .eq(UserNotification::getReadStatus, 0)
                .set(UserNotification::getReadStatus, 1)
                .set(UserNotification::getUpdateTime, LocalDateTime.now()));
    }

    private Long requireUserId() {
        Long userId = UserHolder.getUserId();
        if (userId == null) {
            throw new BusinessException("请先登录");
        }
        return userId;
    }

    private Page<UserNotificationVO> toNotificationVOPage(Page<UserNotification> notificationPage) {
        Set<Long> senderIds = notificationPage.getRecords().stream()
                .map(UserNotification::getSenderUserId)
                .collect(java.util.stream.Collectors.toSet());
        Map<Long, String> nicknameMap = new HashMap<>();
        if (!senderIds.isEmpty()) {
            for (User user : userMapper.selectBatchIds(senderIds)) {
                String nickname = user.getNickname();
                nicknameMap.put(user.getId(), nickname == null || nickname.isBlank() ? user.getUsername() : nickname);
            }
        }

        List<UserNotificationVO> records = notificationPage.getRecords().stream().map(notification -> {
            UserNotificationVO vo = new UserNotificationVO();
            vo.setId(notification.getId());
            vo.setSenderUserId(notification.getSenderUserId());
            vo.setSenderNickname(nicknameMap.getOrDefault(notification.getSenderUserId(), "已注销用户"));
            vo.setType(notification.getType());
            vo.setArticleId(notification.getArticleId());
            vo.setCommentId(notification.getCommentId());
            vo.setContent(notification.getContent());
            vo.setReadStatus(notification.getReadStatus());
            vo.setCreateTime(notification.getCreateTime());
            return vo;
        }).toList();
        Page<UserNotificationVO> page = new Page<>(notificationPage.getCurrent(), notificationPage.getSize(), notificationPage.getTotal());
        page.setRecords(records);
        return page;
    }

    private void pushAfterTransactionCommit(UserNotification notification) {
        NotificationPushMessage message = new NotificationPushMessage();
        message.setId(notification.getId());
        message.setSenderUserId(notification.getSenderUserId());
        message.setType(notification.getType());
        message.setArticleId(notification.getArticleId());
        message.setCommentId(notification.getCommentId());
        message.setContent(notification.getContent());
        message.setReadStatus(notification.getReadStatus());
        message.setCreateTime(notification.getCreateTime());
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                notificationWebSocketHandler.push(notification.getRecipientUserId(), message);
            }
        });
    }
}
