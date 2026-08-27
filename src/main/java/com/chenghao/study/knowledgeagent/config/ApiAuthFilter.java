package com.chenghao.study.knowledgeagent.config;

import com.chenghao.study.knowledgeagent.service.AuthService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * API 认证过滤器：拦截 /api/** 请求校验 Token（登录接口与静态页面放行）
 */
@Slf4j
@Component
public class ApiAuthFilter extends OncePerRequestFilter {

    @Autowired
    private AuthService authService;

    @Autowired
    private AuthProperties authProperties;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();

        // 未启用认证、非 API 请求（静态资源放行）、登录接口本身：直接通过
        if (!authProperties.isEnabled() || !path.startsWith("/api/") || path.equals("/api/login")) {
            chain.doFilter(request, response);
            return;
        }

        String token = request.getHeader("X-Auth-Token");
        String username = authService.validate(token);
        if (username == null) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"success\":false,\"error\":\"未登录或登录已失效\"}");
            return;
        }

        // 校验通过，把用户名带给后续接口
        request.setAttribute("currentUser", username);
        chain.doFilter(request, response);
    }
}
