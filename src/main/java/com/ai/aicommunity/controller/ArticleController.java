package com.ai.aicommunity.controller;

import com.ai.aicommunity.common.Result;
import com.ai.aicommunity.dto.ArticleDTO;
import com.ai.aicommunity.entity.Article;
import com.ai.aicommunity.service.ArticleInteractionService;
import com.ai.aicommunity.service.ArticleService;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/articles")
public class ArticleController {

    private final ArticleService articleService;
    private final ArticleInteractionService articleInteractionService;

    public ArticleController(ArticleService articleService,
                             ArticleInteractionService articleInteractionService) {
        this.articleService = articleService;
        this.articleInteractionService = articleInteractionService;
    }

    @PostMapping
    public Result<String> create(@Valid @RequestBody ArticleDTO dto) {
        articleService.create(dto);
        return Result.success("发布成功");
    }

    @GetMapping
    public Result<Page<Article>> list(
            @RequestParam(defaultValue = "1") Integer current,
            @RequestParam(defaultValue = "10") Integer size) {
        return Result.success(articleService.page(current, size));
    }

    @GetMapping("/hot")
    public Result<List<Article>> hot(@RequestParam(defaultValue = "10") Integer limit) {
        return Result.success(articleInteractionService.hotArticles(limit));
    }

    @GetMapping("/{id}")
    public Result<Article> detail(@PathVariable Long id) {
        return Result.success(articleService.detail(id));
    }

    @PostMapping("/{id}/likes")
    public Result<String> like(@PathVariable Long id) {
        articleInteractionService.like(id);
        return Result.success("点赞成功");
    }

    @DeleteMapping("/{id}/likes")
    public Result<String> unlike(@PathVariable Long id) {
        articleInteractionService.unlike(id);
        return Result.success("已取消点赞");
    }

    @PostMapping("/{id}/favorites")
    public Result<String> favorite(@PathVariable Long id) {
        articleInteractionService.favorite(id);
        return Result.success("收藏成功");
    }

    @DeleteMapping("/{id}/favorites")
    public Result<String> unfavorite(@PathVariable Long id) {
        articleInteractionService.unfavorite(id);
        return Result.success("已取消收藏");
    }

    @PutMapping("/{id}")
    public Result<String> update(@PathVariable Long id, @Valid @RequestBody ArticleDTO dto) {
        articleService.update(id, dto);
        return Result.success("修改成功");
    }

    @DeleteMapping("/{id}")
    public Result<String> delete(@PathVariable Long id) {
        articleService.delete(id);
        return Result.success("删除成功");
    }
}
