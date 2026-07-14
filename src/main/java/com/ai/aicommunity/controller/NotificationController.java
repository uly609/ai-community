package com.ai.aicommunity.controller;

import com.ai.aicommunity.common.Result;
import com.ai.aicommunity.service.NotificationService;
import com.ai.aicommunity.vo.UserNotificationVO;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/notifications")
public class NotificationController {

    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @GetMapping
    public Result<Page<UserNotificationVO>> mine(
            @RequestParam(defaultValue = "1") Integer current,
            @RequestParam(defaultValue = "10") Integer size) {
        return Result.success(notificationService.myNotifications(current, size));
    }

    @PutMapping("/{id}/read")
    public Result<String> markRead(@PathVariable Long id) {
        notificationService.markRead(id);
        return Result.success("已读");
    }

    @PutMapping("/read-all")
    public Result<String> markAllRead() {
        notificationService.markAllRead();
        return Result.success("全部已读");
    }
}
