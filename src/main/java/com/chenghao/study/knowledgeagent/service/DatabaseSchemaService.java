package com.chenghao.study.knowledgeagent.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据库表结构服务：从 MySQL 读取表结构信息，供 AI 生成 SQL 时参考。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DatabaseSchemaService {

    private final JdbcTemplate jdbcTemplate;

    @Value("${knowledge.agent.database.enabled:true}")
    private boolean databaseEnabled;

    /** 缓存的表结构描述（启动时加载，避免每次查询都访问元数据） */
    private volatile String cachedSchemaDescription;

    @PostConstruct
    public void init() {
        if (!databaseEnabled) {
            log.info("数据库查询功能已禁用");
            return;
        }
        try {
            cachedSchemaDescription = buildSchemaDescription();
            log.info("数据库表结构加载完成：\n{}", cachedSchemaDescription);
        } catch (Exception e) {
            log.warn("数据库表结构加载失败，SQL 查询路径不可用：{}", e.getMessage());
            cachedSchemaDescription = null;
        }
    }

    /** 数据库功能是否可用（配置启用 + 表结构加载成功） */
    public boolean isEnabled() {
        return databaseEnabled && cachedSchemaDescription != null;
    }

    /** 获取表结构描述文本（供 AI 提示词使用） */
    public String getSchemaDescription() {
        return cachedSchemaDescription;
    }

    /**
     * 构建所有表的结构描述：表名 + 字段列表（名称、类型、注释）
     */
    private String buildSchemaDescription() {
        // 1. 获取所有表名
        List<String> tables = jdbcTemplate.queryForList(
                "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = DATABASE()",
                String.class
        );

        if (tables.isEmpty()) {
            return "当前数据库中没有表。";
        }

        StringBuilder sb = new StringBuilder();
        for (String tableName : tables) {
            // 2. 获取表注释
            String tableComment = jdbcTemplate.queryForObject(
                    "SELECT TABLE_COMMENT FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?",
                    String.class,
                    tableName
            );

            sb.append("表名: ").append(tableName);
            if (tableComment != null && !tableComment.isEmpty()) {
                sb.append("（").append(tableComment).append("）");
            }
            sb.append("\n");

            // 3. 获取字段信息
            List<Map<String, Object>> columns = jdbcTemplate.queryForList(
                    "SELECT COLUMN_NAME, COLUMN_TYPE, COLUMN_COMMENT, COLUMN_KEY " +
                            "FROM INFORMATION_SCHEMA.COLUMNS " +
                            "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? " +
                            "ORDER BY ORDINAL_POSITION",
                    tableName
            );

            for (Map<String, Object> col : columns) {
                sb.append("  - ").append(col.get("COLUMN_NAME"))
                        .append(" ").append(col.get("COLUMN_TYPE"));
                String comment = (String) col.get("COLUMN_COMMENT");
                if (comment != null && !comment.isEmpty()) {
                    sb.append(" COMMENT '").append(comment).append("'");
                }
                String key = (String) col.get("COLUMN_KEY");
                if ("PRI".equals(key)) {
                    sb.append(" [主键]");
                }
                sb.append("\n");
            }
            sb.append("\n");
        }

        return sb.toString().trim();
    }
}
