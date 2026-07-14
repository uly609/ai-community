package com.ai.aicommunity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ArticleCommentDTO {

    @NotBlank(message = "评论内容不能为空")
    @Size(max = 500, message = "评论不能超过500个字符")
    private String content;

    /** 不传或传 0 时，表示发表一级评论。 */
    private Long parentId;
}
