package com.ai.aicommunity.controller;

import com.ai.aicommunity.common.Result;
import com.ai.aicommunity.dto.ArticleDTO;
import com.ai.aicommunity.entity.Article;
import com.ai.aicommunity.service.ArticleService;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/articles")
public class ArticleController {

    private final ArticleService articleService;

    public ArticleController(ArticleService articleService) {
        this.articleService = articleService;
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

    @GetMapping("/{id}")
    public Result<Article> detail(@PathVariable Long id) {
        return Result.success(articleService.detail(id));
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
