package com.ai.aicommunity.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("article_favorite")
public class ArticleFavorite {

    private Long id;

    private Long articleId;

    private Long userId;

    private LocalDateTime createTime;
}
