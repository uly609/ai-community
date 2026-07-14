package com.ai.aicommunity.controller;

import com.ai.aicommunity.common.Result;
import com.ai.aicommunity.dto.ArticleCommentDTO;
import com.ai.aicommunity.service.ArticleCommentService;
import com.ai.aicommunity.vo.ArticleCommentVO;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/articles/{articleId}/comments")
public class ArticleCommentController {

    private final ArticleCommentService articleCommentService;

    public ArticleCommentController(ArticleCommentService articleCommentService) {
        this.articleCommentService = articleCommentService;
    }

    @PostMapping
    public Result<Long> create(@PathVariable Long articleId,
                               @Valid @RequestBody ArticleCommentDTO dto) {
        return Result.success(articleCommentService.create(articleId, dto));
    }

    @GetMapping
    public Result<Page<ArticleCommentVO>> list(@PathVariable Long articleId,
                                              @RequestParam(defaultValue = "1") Integer current,
                                              @RequestParam(defaultValue = "10") Integer size) {
        return Result.success(articleCommentService.page(articleId, current, size));
    }

    @DeleteMapping("/{commentId}")
    public Result<String> delete(@PathVariable Long articleId, @PathVariable Long commentId) {
        articleCommentService.delete(articleId, commentId);
        return Result.success("删除成功");
    }
}
