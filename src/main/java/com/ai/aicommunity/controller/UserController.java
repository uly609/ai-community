package com.ai.aicommunity.controller;

import com.ai.aicommunity.common.Result;
import com.ai.aicommunity.dto.RegisterDTO;
import com.ai.aicommunity.entity.User;
import com.ai.aicommunity.service.UserService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping
    public Result<List<User>> list() {
        return Result.success(userService.list());
    }

    @PostMapping("/register")
    public Result<String> register(@RequestBody RegisterDTO dto) {
        userService.register(dto);
        return Result.success("注册成功");
    }
}
