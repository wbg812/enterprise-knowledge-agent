package com.chenghao.study.knowledgeagent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 登录认证配置：账号清单在 application.yml 的 knowledge.agent.auth 下维护
 */
@Data
@Component
@ConfigurationProperties(prefix = "knowledge.agent.auth")
public class AuthProperties {

    /** 是否启用登录认证 */
    private boolean enabled = true;

    /** 账号清单：用户名 -> 密码 */
    private Map<String, String> users = new HashMap<>();
}
