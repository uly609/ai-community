package com.ai.aicommunity.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("article_comment")
public class ArticleComment {

    private Long id;

    private Long articleId;

    private Long userId;

    /**
     * 0 表示一级评论；非 0 表示回复的父评论 ID。
     */
    private Long parentId;

    private Long replyUserId;

    private String content;

    /** 1: 正常展示，0: 已删除。 */
    private Integer status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
