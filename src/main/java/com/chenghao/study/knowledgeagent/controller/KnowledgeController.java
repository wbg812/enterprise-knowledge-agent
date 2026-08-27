package com.chenghao.study.knowledgeagent.controller;

import com.chenghao.study.knowledgeagent.config.ChatModelProvider;
import com.chenghao.study.knowledgeagent.dto.ChatResult;
import com.chenghao.study.knowledgeagent.service.ChatService;
import com.chenghao.study.knowledgeagent.service.DocumentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class KnowledgeController {

    private final ChatService chatService;
    private final DocumentService documentService;
    private final ChatModelProvider chatModelProvider;

    /**
     * 智能问答接口（支持 sessionId 多轮记忆，返回引用来源）
     */
    @PostMapping("/chat")
    public ResponseEntity<Map<String, Object>> chat(@RequestBody Map<String, String> request) {
        String message = request.get("message");
        String sessionId = request.get("sessionId");
        if (message == null || message.trim().isEmpty()) {
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("error", "消息不能为空");
            return ResponseEntity.badRequest().body(error);
        }

        Map<String, Object> response = new HashMap<>();
        try {
            ChatResult result = chatService.chat(sessionId, message);
            response.put("success", true);
            response.put("answer", result.getAnswer());

            List<Map<String, Object>> sources = new ArrayList<>();
            for (ChatResult.Source source : result.getSources()) {
                Map<String, Object> item = new HashMap<>();
                item.put("fileName", source.getFileName());
                item.put("score", Math.round(source.getScore() * 1000) / 1000.0);
                sources.add(item);
            }
            response.put("sources", sources);
        } catch (Exception e) {
            log.error("问答失败", e);
            response.put("success", false);
            response.put("error", "问答失败：" + e.getMessage());
        }

        return ResponseEntity.ok(response);
    }

    /**
     * 清空会话记忆（开启新对话）
     */
    @DeleteMapping("/chat/{sessionId}")
    public ResponseEntity<Map<String, Object>> clearChatSession(@PathVariable String sessionId) {
        chatService.clearSession(sessionId);
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("message", "会话记忆已清空");
        return ResponseEntity.ok(response);
    }

    /**
     * 上传文档接口
     */
    @PostMapping("/upload")
    public ResponseEntity<Map<String, Object>> uploadFile(@RequestParam("file") MultipartFile file) {
        Map<String, Object> response = new HashMap<>();

        try {
            // 保存文件到 docs/input 目录
            Path uploadPath = Paths.get("docs/input");
            if (!Files.exists(uploadPath)) {
                Files.createDirectories(uploadPath);
            }

            Path filePath = uploadPath.resolve(file.getOriginalFilename());
            Files.copy(file.getInputStream(), filePath, StandardCopyOption.REPLACE_EXISTING);

            // 仅向量化新增文档，避免重复向量化
            documentService.ingestNewDocuments();

            response.put("success", true);
            response.put("message", "文档上传成功并已建立索引");
        } catch (IOException e) {
            log.error("文件上传失败", e);
            response.put("success", false);
            response.put("error", "上传失败：" + e.getMessage());
        }

        return ResponseEntity.ok(response);
    }

    /**
     * 获取文档列表
     */
    @GetMapping("/documents")
    public ResponseEntity<Map<String, Object>> listDocuments() {
        Map<String, Object> response = new HashMap<>();
        try {
            Path inputPath = Paths.get("docs/input");
            if (!Files.exists(inputPath)) {
                response.put("success", true);
                response.put("documents", new ArrayList<>());
                return ResponseEntity.ok(response);
            }

            List<String> files = Files.list(inputPath)
                    .map(Path::getFileName)
                    .map(name -> name.toString())
                    .collect(Collectors.toList());

            response.put("success", true);
            response.put("documents", files);
        } catch (IOException e) {
            log.error("获取文档列表失败", e);
            response.put("success", false);
            response.put("error", "获取失败：" + e.getMessage());
        }

        return ResponseEntity.ok(response);
    }

    /**
     * 删除文档
     */
    @DeleteMapping("/documents/{filename}")
    public ResponseEntity<Map<String, Object>> deleteDocument(@PathVariable String filename) {
        Map<String, Object> response = new HashMap<>();
        try {
            Path filePath = Paths.get("docs/input", filename);
            if (Files.exists(filePath)) {
                Files.delete(filePath);
                // 重建索引，清理被删文档的残留向量
                documentService.rebuildIndex();
                response.put("success", true);
                response.put("message", "文档已删除");
            } else {
                response.put("success", false);
                response.put("error", "文档不存在");
            }
        } catch (IOException e) {
            log.error("删除文档失败", e);
            response.put("success", false);
            response.put("error", "删除失败：" + e.getMessage());
        }

        return ResponseEntity.ok(response);
    }

    /**
     * 更新 API 配置（Base URL / Key / 模型），立即生效无需重启
     */
    @PostMapping("/settings")
    public ResponseEntity<Map<String, Object>> updateSettings(@RequestBody Map<String, String> request) {
        Map<String, Object> response = new HashMap<>();
        String baseUrl = request.get("baseUrl");
        String apiKey = request.get("apiKey");
        String model = request.get("model");

        if (apiKey == null || apiKey.trim().isEmpty()) {
            response.put("success", false);
            response.put("error", "API Key 不能为空");
            return ResponseEntity.badRequest().body(response);
        }

        try {
            chatModelProvider.update(baseUrl, apiKey.trim(), model == null || model.trim().isEmpty() ? "gpt-3.5-turbo" : model.trim());
            response.put("success", true);
            response.put("message", "配置已更新并立即生效");
        } catch (Exception e) {
            log.error("更新配置失败", e);
            response.put("success", false);
            response.put("error", "更新失败：" + e.getMessage());
        }

        return ResponseEntity.ok(response);
    }

    /**
     * 获取系统状态
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("processedDocuments", documentService.getProcessedCount());
        response.put("message", "系统运行正常");
        return ResponseEntity.ok(response);
    }
}
