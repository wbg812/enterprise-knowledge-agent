package com.chenghao.study.knowledgeagent.controller;

import com.chenghao.study.knowledgeagent.service.AuthService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.Map;

/**
 * 认证接口：登录 / 注销 / 当前用户
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    /**
     * 登录：账号密码换 Token
     */
    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody Map<String, String> request) {
        Map<String, Object> response = new HashMap<>();
        String token = authService.login(request.get("username"), request.get("password"));
        if (token == null) {
            response.put("success", false);
            response.put("error", "用户名或密码错误");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(response);
        }
        response.put("success", true);
        response.put("token", token);
        response.put("username", request.get("username").trim());
        return ResponseEntity.ok(response);
    }

    /**
     * 注销：使当前 Token 失效
     */
    @PostMapping("/logout")
    public ResponseEntity<Map<String, Object>> logout(
            @RequestHeader(value = "X-Auth-Token", required = false) String token) {
        authService.logout(token);
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("message", "已注销");
        return ResponseEntity.ok(response);
    }

    /**
     * 当前登录用户（用户名由认证过滤器校验 Token 后写入）
     */
    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> me(HttpServletRequest request) {
        Map<String, Object> response = new HashMap<>();
        Object username = request.getAttribute("currentUser");
        response.put("success", true);
        response.put("username", username != null ? username.toString() : "guest");
        return ResponseEntity.ok(response);
    }
}
