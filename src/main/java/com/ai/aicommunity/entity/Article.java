package com.ai.aicommunity.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("article")
public class Article {

    private Long id;

    /**
     * 发布用户ID，来自JWT解析后的UserHolder
     */
    private Long userId;

    private String title;

    private String content;

    /**
     * AI生成的文章摘要
     */
    private String aiSummary;

    private Integer viewCount;

    private Integer likeCount;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
