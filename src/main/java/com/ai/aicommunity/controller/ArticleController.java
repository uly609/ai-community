package com.ai.aicommunity.controller;

import com.ai.aicommunity.dto.ArticleDTO;
import com.ai.aicommunity.entity.Article;
import com.ai.aicommunity.service.ArticleService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

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
    public List<Article> list() {
        return articleService.list();
    }

    @GetMapping("/{id}")
    public Article detail(@PathVariable Long id) {
        return articleService.detail(id);
    }
}
