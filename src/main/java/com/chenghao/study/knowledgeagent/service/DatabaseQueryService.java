package com.chenghao.study.knowledgeagent.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据库查询服务：执行 AI 生成的 SQL 并返回结果。
 * <p>
 * 安全约束：仅允许 SELECT 查询，禁止任何写操作。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DatabaseQueryService {

    private final JdbcTemplate jdbcTemplate;

    @Value("${knowledge.agent.database.max-rows:500}")
    private int maxRows;

    /**
     * 执行 SQL 查询并返回结果列表。
     *
     * @param sql 仅允许 SELECT 语句
     * @return 查询结果，每行为一个 Map（列名 → 值）
     * @throws SQLException 如果不是 SELECT 或执行失败
     */
    public List<Map<String, Object>> executeQuery(String sql) throws SQLException {
        String trimmed = sql.trim();

        // 安全检查：只允许 SELECT
        if (!trimmed.toUpperCase().startsWith("SELECT")) {
            throw new SQLException("安全限制：仅允许 SELECT 查询，禁止写操作");
        }

        // 禁止危险关键字
        String upper = trimmed.toUpperCase();
        if (upper.contains("INSERT") || upper.contains("UPDATE") || upper.contains("DELETE")
                || upper.contains("DROP") || upper.contains("ALTER") || upper.contains("CREATE")
                || upper.contains("TRUNCATE")) {
            throw new SQLException("安全限制：SQL 包含禁止关键字");
        }

        log.info("执行数据库查询：{}", trimmed);
        List<Map<String, Object>> results = jdbcTemplate.queryForList(trimmed);

        // 限制返回行数，防止大结果集
        if (results.size() > maxRows) {
            results = results.subList(0, maxRows);
            log.info("查询结果已截断至 {} 行", maxRows);
        }

        log.info("查询返回 {} 行数据", results.size());
        return results;
    }

    /**
     * 将查询结果格式化为文本（供 AI 生成自然语言回答）
     */
    public static String formatResultsAsText(List<Map<String, Object>> results) {
        if (results == null || results.isEmpty()) {
            return "查询结果为空，没有匹配的数据。";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("共 ").append(results.size()).append(" 条记录：\n");

        // 表头
        Map<String, Object> firstRow = results.get(0);
        List<String> columns = new ArrayList<>(firstRow.keySet());
        sb.append(String.join(" | ", columns)).append("\n");

        // 分隔线
        int sepLen = Math.min(columns.size() * 15, 100);
        for (int i = 0; i < sepLen; i++) sb.append('-');
        sb.append('\n');

        // 数据行
        for (Map<String, Object> row : results) {
            List<String> values = new ArrayList<>();
            for (String col : columns) {
                Object val = row.get(col);
                values.add(val == null ? "NULL" : val.toString());
            }
            sb.append(String.join(" | ", values)).append("\n");
        }

        return sb.toString();
    }
}
