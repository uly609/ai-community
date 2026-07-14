package com.ai.aicommunity.vo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ArticleCommentVO {

    private Long id;
    private Long articleId;
    private Long userId;
    private String userNickname;
    private Long parentId;
    private Long replyUserId;
    private String replyUserNickname;
    private String content;
    private Integer status;
    private Boolean canDelete;
    private LocalDateTime createTime;
}
