package com.chenghao.study.knowledgeagent.config;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.time.Duration;

/**
 * 聊天模型提供者：启动时从 application.yml 读取配置，
 * 运行中可通过页面设置动态更新，无需重启服务
 */
@Slf4j
@Component
public class ChatModelProvider {

    @Value("${langchain4j.openai.api-key:}")
    private String openAiApiKey;

    @Value("${langchain4j.openai.base-url:}")
    private String openAiBaseUrl;

    @Value("${langchain4j.openai.model:gpt-3.5-turbo}")
    private String openAiModel;

    /** 温度参数：kimi-k3 等推理模型只允许 1，其他模型可设低值减少发散 */
    @Value("${langchain4j.openai.temperature:1}")
    private double temperature;

    /** 请求超时（秒）：推理模型 + 大上下文时耗时较长，默认 180 秒 */
    @Value("${langchain4j.openai.timeout-seconds:180}")
    private long timeoutSeconds;

    private volatile ChatLanguageModel chatModel;

    @PostConstruct
    public void init() {
        rebuild(openAiBaseUrl, openAiApiKey, openAiModel);
        log.info("聊天模型初始化完成，baseUrl: {}, model: {}", openAiBaseUrl, openAiModel);
    }

    public ChatLanguageModel getModel() {
        return chatModel;
    }

    /**
     * 更新 API 配置并重建聊天模型，立即生效
     */
    public synchronized void update(String baseUrl, String apiKey, String model) {
        rebuild(baseUrl, apiKey, model);
        this.openAiBaseUrl = baseUrl;
        this.openAiApiKey = apiKey;
        this.openAiModel = model;
        log.info("API 配置已更新，baseUrl: {}, model: {}", baseUrl, model);
    }

    private void rebuild(String baseUrl, String apiKey, String model) {
        OpenAiChatModel.OpenAiChatModelBuilder builder = OpenAiChatModel.builder()
                .apiKey(apiKey)
                .modelName(model)
                .temperature(temperature)
                .timeout(Duration.ofSeconds(timeoutSeconds));
        if (baseUrl != null && !baseUrl.trim().isEmpty()) {
            builder.baseUrl(baseUrl.trim());
        }
        this.chatModel = builder.build();
    }

    /**
     * 带限流重试的调用：遇到 RPM 限制时自动等待后重试
     * 免费版 Moonshot 限制 3 RPM，每次等待 20 秒确保窗口重置
     */
    public String generateWithRetry(String prompt) {
        int maxRetries = 5;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                return chatModel.generate(prompt);
            } catch (Exception e) {
                String msg = e.getMessage();
                if (msg != null && (msg.contains("rate_limit") || msg.contains("RPM"))
                        && attempt < maxRetries) {
                    long waitSeconds = 20L; // 固定等待 20 秒，确保 1 分钟窗口重置
                    log.warn("API 限流，等待 {} 秒后重试（第 {}/{} 次）", waitSeconds, attempt + 1, maxRetries);
                    try {
                        Thread.sleep(waitSeconds * 1000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("重试被中断", ie);
                    }
                } else {
                    throw e;
                }
            }
        }
        throw new RuntimeException("重试次数已耗尽");
    }
}
