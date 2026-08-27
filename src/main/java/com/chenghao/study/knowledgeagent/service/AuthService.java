package com.chenghao.study.knowledgeagent.service;

import com.chenghao.study.knowledgeagent.config.AuthProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 认证服务：登录校验与 Token 会话管理（内存态，重启后需重新登录）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final AuthProperties authProperties;

    /** 有效 Token -> 用户名 */
    private final Map<String, String> tokenToUser = new ConcurrentHashMap<>();

    /**
     * 登录：校验账号密码，成功返回 Token，失败返回 null
     */
    public String login(String username, String password) {
        if (username == null || password == null) {
            return null;
        }
        String expected = authProperties.getUsers().get(username.trim());
        if (expected == null || !expected.equals(password)) {
            log.warn("登录失败：{}", username);
            return null;
        }
        String token = UUID.randomUUID().toString().replace("-", "");
        tokenToUser.put(token, username.trim());
        log.info("用户登录成功：{}", username);
        return token;
    }

    /**
     * 校验 Token，有效返回用户名，否则返回 null
     */
    public String validate(String token) {
        if (token == null || token.isEmpty()) {
            return null;
        }
        return tokenToUser.get(token);
    }

    /**
     * 注销：使 Token 失效
     */
    public void logout(String token) {
        if (token == null) {
            return;
        }
        String username = tokenToUser.remove(token);
        if (username != null) {
            log.info("用户注销：{}", username);
        }
    }
}
