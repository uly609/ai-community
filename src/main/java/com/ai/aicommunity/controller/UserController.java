package com.ai.aicommunity.controller;

import com.ai.aicommunity.common.Result;
import com.ai.aicommunity.dto.LoginDTO;
import com.ai.aicommunity.dto.RegisterDTO;
import com.ai.aicommunity.entity.User;
import com.ai.aicommunity.service.UserService;
import com.ai.aicommunity.utils.UserHolder;
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

    @GetMapping("/me")
    public Result<User> me() {
        return Result.success(userService.getById(UserHolder.getUserId()));
    }

    @PostMapping("/register")
    public Result<String> register(@RequestBody RegisterDTO dto) {
        userService.register(dto);
        return Result.success("注册成功");
    }

    @PostMapping("/login")
    public Result<String> login(@RequestBody LoginDTO dto) {
        return Result.success(userService.login(dto));
    }
}
