package com.ai.aicommunity.service;

import com.ai.aicommunity.dto.RegisterDTO;
import com.ai.aicommunity.entity.User;
import com.ai.aicommunity.exception.BusinessException;
import com.ai.aicommunity.mapper.UserMapper;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class UserService {

    private final UserMapper userMapper;

    public UserService(UserMapper userMapper) {
        this.userMapper = userMapper;
    }

    public List<User> list() {
        return userMapper.selectList(null);
    }

    public void register(RegisterDTO dto) {

        User user = new User();
        user.setUsername(dto.getUsername());
        user.setPassword(dto.getPassword());

        userMapper.insert(user);
    }
}
