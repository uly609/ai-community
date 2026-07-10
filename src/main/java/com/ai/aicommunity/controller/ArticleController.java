package com.ai.aicommunity.controller;

import com.ai.aicommunity.dto.ArticleDTO;
import com.ai.aicommunity.entity.Article;
import com.ai.aicommunity.service.ArticleService;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/articles")
public class ArticleController {

    private final ArticleService articleService;

    public ArticleController(ArticleService articleService) {
        this.articleService = articleService;
    }

    @PostMapping
    public String create(@RequestBody ArticleDTO dto) {
        articleService.create(dto);
        return "success";
    }

    @GetMapping
    public Page<Article> list(
            @RequestParam(defaultValue = "1") Integer current,
            @RequestParam(defaultValue = "10") Integer size) {
        return articleService.page(current, size);
    }

    @GetMapping("/{id}")
    public Article detail(@PathVariable Long id) {
        return articleService.detail(id);
    }
}
