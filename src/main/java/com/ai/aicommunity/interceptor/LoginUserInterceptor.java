package com.ai.aicommunity.interceptor;

import com.ai.aicommunity.utils.JwtUtil;
import com.ai.aicommunity.utils.UserHolder;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

public class LoginUserInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String token = request.getHeader("Authorization");

        if (token == null || token.isEmpty()) {
            return false;
        }

        Long userId = JwtUtil.getUserId(token.replace("Bearer ", ""));
        UserHolder.saveUserId(userId);

        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        UserHolder.remove();
    }
}
