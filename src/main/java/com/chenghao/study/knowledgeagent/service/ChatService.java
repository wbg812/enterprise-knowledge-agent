package com.chenghao.study.knowledgeagent.service;

import com.chenghao.study.knowledgeagent.config.ChatModelProvider;
import com.chenghao.study.knowledgeagent.dto.ChatResult;
import com.chenghao.study.knowledgeagent.dto.SearchHit;
import com.chenghao.study.knowledgeagent.tool.AgentTools;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.service.tool.DefaultToolExecutor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    private final ChatModelProvider chatModelProvider;
    private final SearchService searchService;
    private final DatabaseSchemaService databaseSchemaService;
    private final DatabaseQueryService databaseQueryService;

    /** 工具实例和工具规范（启动时初始化） */
    private final AgentTools agentTools = new AgentTools();
    private final List<ToolSpecification> toolSpecs = ToolSpecifications.toolSpecificationsFrom(AgentTools.class);

    @Value("${knowledge.agent.top-k:3}")
    private int topK;

    /** 每个会话最多保留的历史对话轮数（滑动窗口） */
    private static final int MAX_HISTORY_ROUNDS = 5;

    /** 历史回答写入提示词时的最大保留字符数，防止上下文膨胀 */
    private static final int MAX_HISTORY_ANSWER_CHARS = 500;

    /** 会话对话历史：sessionId -> 问答对列表（多轮记忆） */
    private final Map<String, LinkedList<String[]>> sessionHistories = new ConcurrentHashMap<>();

    /** 记录上一次走数据库路径的会话（用于追问上下文感知） */
    private final Map<String, Boolean> lastQueryWasDatabase = new ConcurrentHashMap<>();

    /**
     * 基于知识库的问答（支持多轮对话记忆与来源引用）
     */
    public ChatResult chat(String sessionId, String userMessage) {
        // 0. 路由：数据库查询 vs 文档检索
        boolean dbEnabled = databaseSchemaService.isEnabled();
        boolean keywordMatch = isDataQuery(userMessage);
        boolean isDocQuery = isDocRelatedQuery(userMessage);
        // 会话上下文：如果上一轮走了数据库路径，追问也默认走数据库（但文档类问题除外）
        boolean contextMatch = sessionId != null && Boolean.TRUE.equals(lastQueryWasDatabase.get(sessionId)) && !isDocQuery;
        boolean isData = keywordMatch || contextMatch;
        log.info("路由判断：数据库可用={}, 关键词匹配={}, 文档类={}, 上下文匹配={}, 问题={}", dbEnabled, keywordMatch, isDocQuery, contextMatch, userMessage);
        if (dbEnabled && isData) {
            try {
                ChatResult dbResult = chatViaDatabase(sessionId, userMessage);
                if (dbResult != null) {
                    // 记录本轮走了数据库路径（供追问上下文感知）
                    if (sessionId != null) {
                        lastQueryWasDatabase.put(sessionId, true);
                    }
                    return dbResult;
                }
                // 数据库查询失败，回退到 RAG
            } catch (Exception e) {
                log.warn("数据库查询路径失败，回退到 RAG：{}", e.getMessage());
            }
        }

        // 本轮走了 RAG 路径，重置上下文标记
        if (sessionId != null) {
            lastQueryWasDatabase.put(sessionId, false);
        }

        // 0.5 工具调用：关键词直接匹配（不依赖模型 function calling）
        ChatResult toolDirect = tryDirectTool(sessionId, userMessage);
        if (toolDirect != null) {
            return toolDirect;
        }

        // 1. 检索：统计类走关键词全量召回，其他走混合检索
        boolean isStatistical = SearchService.isStatisticalQuery(userMessage);
        List<TextSegment> segments;
        Map<String, Double> sourceScores = new LinkedHashMap<>();

        if (isStatistical) {
            // 统计类：直接取所有关键词命中片段（不限 top-k），让大模型看到完整数据
            segments = searchService.keywordAllSearch(userMessage);
            // 来源：按文档去重，不记录分数
            for (TextSegment segment : segments) {
                String fileName = segment.metadata("file_name");
                if (fileName == null || fileName.isEmpty()) {
                    fileName = "未知文档";
                }
                sourceScores.putIfAbsent(fileName, 1.0);
            }
        } else {
            // 普通问题：混合检索 + RRF 融合
            List<SearchHit> hits = searchService.hybridSearch(userMessage, topK);
            segments = hits.stream().map(SearchHit::getSegment).collect(java.util.stream.Collectors.toList());
            // 来源：按文档去重，保留最高相关度得分
            for (SearchHit hit : hits) {
                String fileName = hit.getSegment().metadata("file_name");
                if (fileName == null || fileName.isEmpty()) {
                    fileName = "未知文档";
                }
                sourceScores.merge(fileName, hit.getScore(), Math::max);
            }
        }
        List<ChatResult.Source> sources = new ArrayList<>();
        for (Map.Entry<String, Double> entry : sourceScores.entrySet()) {
            sources.add(new ChatResult.Source(entry.getKey(), entry.getValue()));
        }

        // 3. 构建上下文提示词
        StringBuilder contextBuilder = new StringBuilder();
        contextBuilder.append("以下是企业知识库中的相关信息：\n\n");

        if (segments.isEmpty()) {
            // RAG 未找到文档，尝试工具调用
            log.info("RAG 未找到文档，尝试工具调用");
            ChatResult toolResult = chatWithTools(sessionId, userMessage);
            if (toolResult != null) {
                return toolResult;
            }
            contextBuilder.append("未找到相关文档内容。");
        } else {
            for (int i = 0; i < segments.size(); i++) {
                TextSegment segment = segments.get(i);
                contextBuilder.append(String.format("[%d] %s\n\n", i + 1, segment.text()));
            }
        }

        // 4. 拼接历史对话（多轮记忆，用于理解追问中的上下文）
        StringBuilder historyBuilder = new StringBuilder();
        List<String[]> history = sessionId == null ? null : sessionHistories.get(sessionId);
        if (history != null && !history.isEmpty()) {
            historyBuilder.append("之前的对话（仅用于理解用户追问的上下文）：\n");
            for (String[] round : history) {
                historyBuilder.append("用户：").append(round[0]).append("\n");
                historyBuilder.append("助手：").append(truncate(round[1])).append("\n");
            }
            historyBuilder.append("\n");
        }

        // 5. 构建完整提示词（严格约束：只允许基于文档回答，禁止自行推断）
        StringBuilder promptBuilder = new StringBuilder();
        promptBuilder.append("你是一个企业知识库助手。请严格根据以下提供的信息回答用户的问题。\n" +
                "回答要求：\n" +
                "1. 只能使用提供的信息作答，不得添加、推测或推断文档中不存在的内容；\n" +
                "2. 数字、单位、模型名称等必须与文档原文完全一致，不得改动；\n" +
                "3. 文档中没有明确说明的内容，直接回答“文档中未找到相关信息”，不要自行推断。\n");

        // 统计类问题额外指令：先列后数，避免 LLM 计数错误
        if (isStatistical) {
            promptBuilder.append("4. 涉及数量统计时，必须先逐一列出所有相关条目（带编号），再给出总数，确保总数与列出的条目数量一致。\n");
        }

        promptBuilder.append("\n%s%s\n\n用户问题：%s\n\n你的回答：");

        String prompt = String.format(
                promptBuilder.toString(),
                historyBuilder.toString(),
                contextBuilder.toString(),
                userMessage
        );

        // 6. 调用 LLM 生成回答（每次取最新配置的模型，带限流重试）
        String response = chatModelProvider.generateWithRetry(prompt);

        // 7. 写入会话历史（滑动窗口，仅保留最近几轮）
        if (sessionId != null && !sessionId.isEmpty()) {
            LinkedList<String[]> rounds = sessionHistories.computeIfAbsent(sessionId, k -> new LinkedList<>());
            rounds.add(new String[]{userMessage, response});
            while (rounds.size() > MAX_HISTORY_ROUNDS) {
                rounds.removeFirst();
            }
        }

        log.info("查询：{}, 引用来源 {} 个, 返回长度：{}", userMessage, sources.size(), response.length());
        return new ChatResult(response, sources);
    }

    /**
     * 清空会话记忆（开启新对话时调用）
     */
    public void clearSession(String sessionId) {
        sessionHistories.remove(sessionId);
        lastQueryWasDatabase.remove(sessionId);
        log.info("会话记忆已清空：{}", sessionId);
    }

    /** 截断过长的历史回答，防止提示词膨胀 */
    private static String truncate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= MAX_HISTORY_ANSWER_CHARS
                ? text
                : text.substring(0, MAX_HISTORY_ANSWER_CHARS) + "...（已截断）";
    }

    // ==================== 数据库查询路径（阶段三） ====================

    /**
     * 数据库查询路径：AI 生成 SQL → 执行 → AI 格式化结果
     * 用于结构化数据查询（员工信息、部门统计等）
     */
    private ChatResult chatViaDatabase(String sessionId, String userMessage) {
        String schema = databaseSchemaService.getSchemaDescription();

        // 1. 拼接历史对话（用于理解追问上下文）
        StringBuilder historyBuilder = new StringBuilder();
        List<String[]> history = sessionId == null ? null : sessionHistories.get(sessionId);
        if (history != null && !history.isEmpty()) {
            historyBuilder.append("之前的对话（仅用于理解追问上下文）：\n");
            for (String[] round : history) {
                historyBuilder.append("用户：").append(round[0]).append("\n");
                historyBuilder.append("助手：").append(truncate(round[1])).append("\n");
            }
            historyBuilder.append("\n");
        }

        // 2. 构建 SQL 生成提示词
        String sqlPrompt = String.format(
                "%s你是一个 SQL 查询生成助手。根据以下数据库表结构，生成一个 MySQL SELECT 查询来回答用户问题。\n" +
                "要求：\n" +
                "1. 只生成 SELECT 语句，禁止任何写操作\n" +
                "2. 严格按照表结构中的字段名和表名\n" +
                "3. 只输出 SQL 语句本身，不要任何解释、不要 markdown 代码块标记\n" +
                "4. 对于统计类问题（如人数、部门数量），使用 COUNT 或 GROUP BY\n" +
                "5. 对于查询具体记录的问题，返回所有相关字段\n\n" +
                "数据库表结构：\n%s\n\n" +
                "%s用户问题：%s\n\n请生成 SQL 查询：",
                historyBuilder.toString(), schema, "", userMessage
        );

        String sql = chatModelProvider.generateWithRetry(sqlPrompt).trim();

        // 清理可能的 markdown 标记和末尾分号
        sql = sql.replaceAll("(?i)^```sql\\s*", "").replaceAll("(?i)\\s*```$", "").trim();
        while (sql.endsWith(";")) {
            sql = sql.substring(0, sql.length() - 1).trim();
        }
        log.info("AI 生成 SQL：{}", sql);

        if (!sql.toUpperCase().startsWith("SELECT")) {
            log.warn("AI 生成的不是 SELECT 语句，跳过：{}", sql);
            return null;
        }

        // 3. 执行 SQL
        List<Map<String, Object>> results;
        try {
            results = databaseQueryService.executeQuery(sql);
        } catch (SQLException e) {
            log.warn("SQL 执行失败：{}", e.getMessage());
            return null;
        }

        log.info("数据库查询结果：{} 条记录", results.size());

        String resultText = DatabaseQueryService.formatResultsAsText(results);

        // 4. 用 AI 将查询结果格式化为自然语言回答
        String formatPrompt = String.format(
                "你是一个企业知识库助手。以下是数据库查询结果，请将其整理为自然、友好的回答。\n" +
                "要求：\n" +
                "1. 只使用查询结果中的数据，不得添加、推测任何额外信息\n" +
                "2. 数字必须与查询结果完全一致\n" +
                "3. 如果结果为空，如实告知\n" +
                "4. 涉及列举时，逐一列出所有记录，不要省略\n" +
                "5. 回答要简洁清晰\n\n" +
                "用户问题：%s\n\n查询结果：\n%s\n\n你的回答：",
                userMessage, resultText
        );

        String response = chatModelProvider.generateWithRetry(formatPrompt);

        // 5. 写入会话历史
        if (sessionId != null && !sessionId.isEmpty()) {
            LinkedList<String[]> rounds = sessionHistories.computeIfAbsent(sessionId, k -> new LinkedList<>());
            rounds.add(new String[]{userMessage, response});
            while (rounds.size() > MAX_HISTORY_ROUNDS) {
                rounds.removeFirst();
            }
        }

        // 来源标记为数据库
        List<ChatResult.Source> sources = Collections.singletonList(
                new ChatResult.Source("数据库", 1.0)
        );

        log.info("数据库问答完成：问题={}, 结果行数={}", userMessage, results.size());
        return new ChatResult(response, sources);
    }

    /**
     * 判断问题是否关于结构化数据（走 SQL 查询路径）
     */
    private static boolean isDataQuery(String query) {
        // 计算类问题 → 走工具路径，不走 SQL
        if (matchesAny(query, "乘以", "除以", "加上", "减去", "乘", "除")
                && query.matches(".*\\d.*")) {
            return false;
        }

        // 文档类关键词 → 走 RAG
        String[] docKeywords = {"文档", "文件", "方案", "需求", "规范", "制度", "手册",
                "说明", "报告", "计划", "流程", "规定", "标准", "政策"};
        for (String kw : docKeywords) {
            if (query.contains(kw)) {
                return false;
            }
        }

        // 数据类关键词 → 走 SQL
        String[] dataKeywords = {"部门", "员工", "人员", "职位", "职业", "工号", "入职",
                "学历", "电话", "邮箱", "人数", "多少", "几个", "共有", "统计",
                "名单", "花名册", "谁是", "哪个", "哪些", "所有", "全部",
                "他们", "她们", "其中"};
        for (String kw : dataKeywords) {
            if (query.contains(kw)) {
                return true;
            }
        }

        return false;
    }

    /**
     * 判断问题是否关于文档内容（走 RAG 检索路径）
     * 用于在上下文感知时排除文档类问题
     */
    private static boolean isDocRelatedQuery(String query) {
        String[] docKeywords = {"文档", "文件", "方案", "需求", "规范", "制度", "手册",
                "说明", "报告", "计划", "流程", "规定", "标准", "政策",
                "阶段", "部署", "架构", "服务器", "配置", "要求"};
        for (String kw : docKeywords) {
            if (query.contains(kw)) {
                return true;
            }
        }
        return false;
    }

    // ==================== 工具调用（阶段四） ====================

    /**
     * 关键词直接匹配工具（不依赖模型 function calling）。
     * 检测问题是否包含工具关键词，如果匹配则直接执行工具并用 AI 格式化结果。
     */
    private ChatResult tryDirectTool(String sessionId, String userMessage) {
        String toolResult = null;
        String toolName = null;

        // 时间类问题
        if (matchesAny(userMessage, "今天", "几号", "星期", "星期几", "现在几点",
                "当前时间", "什么日期", "哪一天", "今天日期", "现在日期")) {
            toolResult = agentTools.getCurrentDateTime();
            toolName = "getCurrentDateTime";
        }
        // 计算类问题
        else if (matchesAny(userMessage, "等于多少", "是多少", "怎么算", "计算一下",
                "乘以", "除以", "加上", "减去", "总和", "平均", "百分比")) {
            // 提取数学表达式
            String expr = extractMathExpression(userMessage);
            if (expr != null) {
                toolResult = agentTools.calculate(expr);
                toolName = "calculate";
            }
        }

        if (toolResult == null) {
            return null;
        }

        log.info("直接工具匹配：tool={}, question={}", toolName, userMessage);

        // 用 AI 将工具结果格式化为自然语言
        String formatPrompt = String.format(
                "根据以下工具执行结果，用自然、简洁的语言回答用户的问题。\n\n" +
                "用户问题：%s\n工具结果：%s\n\n你的回答：",
                userMessage, toolResult
        );
        String response = chatModelProvider.generateWithRetry(formatPrompt);

        saveHistory(sessionId, userMessage, response);
        return new ChatResult(response, Collections.singletonList(
                new ChatResult.Source("工具：" + toolName, 1.0)
        ));
    }

    /** 检查问题是否匹配任一关键词 */
    private static boolean matchesAny(String query, String... keywords) {
        for (String kw : keywords) {
            if (query.contains(kw)) {
                return true;
            }
        }
        return false;
    }

    /** 从问题中提取数学表达式 */
    private static String extractMathExpression(String query) {
        // 去除常见前缀
        String expr = query
                .replaceAll("请问", "")
                .replaceAll("等于多少.*", "")
                .replaceAll("是多少.*", "")
                .replaceAll("怎么算.*", "")
                .replaceAll("计算一下.*", "")
                .replaceAll(".*?[是:]\\s*", "")
                .trim();

        // 检查是否包含数字
        if (expr.matches(".*\\d.*")) {
            // 替换中文数字运算符
            expr = expr.replace("乘以", "*").replace("除以", "/")
                    .replace("加上", "+").replace("减去", "-")
                    .replace("乘", "*").replace("除", "/");
            return expr;
        }
        return null;
    }

    /**
     * 工具调用路径：AI 自主决定调用哪个工具，根据工具结果生成回答。
     * 当 RAG 未找到文档时自动触发。
     *
     * @return 工具调用结果，如果模型不支持工具或无需调用工具则返回 null
     */
    private ChatResult chatWithTools(String sessionId, String userMessage) {
        try {
            // 1. 构建消息列表
            List<ChatMessage> messages = new ArrayList<>();
            messages.add(SystemMessage.from(
                    "你是一个企业知识库智能助手。你可以使用提供的工具来帮助用户回答问题。\n" +
                    "如果用户的问题需要用到工具（如查询时间、计算等），请调用相应工具。\n" +
                    "如果不需要工具就能回答，直接回答即可。"));
            messages.add(UserMessage.from(userMessage));

            // 2. 调用模型（带工具规范）
            Response<AiMessage> response = chatModelProvider.getModel()
                    .generate(messages, toolSpecs);
            AiMessage aiMessage = response.content();

            // 3. 检查是否需要执行工具
            if (!aiMessage.hasToolExecutionRequests()) {
                // 模型没有调用工具，直接返回文本（说明问题不需要工具）
                log.info("工具调用：模型未调用工具，直接返回文本");
                String text = aiMessage.text();
                saveHistory(sessionId, userMessage, text);
                return new ChatResult(text, Collections.emptyList());
            }

            // 4. 执行工具调用
            for (ToolExecutionRequest request : aiMessage.toolExecutionRequests()) {
                log.info("工具调用请求：name={}, args={}", request.name(), request.arguments());

                // 用 DefaultToolExecutor 执行工具
                DefaultToolExecutor executor = new DefaultToolExecutor(agentTools, request);
                String result = executor.execute(request, null);
                log.info("工具执行结果：{}", result);

                // 将工具结果加入消息列表
                messages.add(aiMessage);
                messages.add(ToolExecutionResultMessage.from(request, result));
            }

            // 5. 带工具结果再次调用模型，生成最终回答
            Response<AiMessage> finalResponse = chatModelProvider.getModel()
                    .generate(messages);
            String finalText = finalResponse.content().text();

            log.info("工具调用完成，最终回答长度：{}", finalText.length());
            saveHistory(sessionId, userMessage, finalText);
            return new ChatResult(finalText, Collections.singletonList(
                    new ChatResult.Source("工具调用", 1.0)
            ));

        } catch (Exception e) {
            // 模型可能不支持工具调用，静默失败
            log.warn("工具调用失败（模型可能不支持）：{}", e.getMessage());
            return null;
        }
    }

    /** 保存会话历史 */
    private void saveHistory(String sessionId, String userMessage, String response) {
        if (sessionId != null && !sessionId.isEmpty()) {
            LinkedList<String[]> rounds = sessionHistories.computeIfAbsent(sessionId, k -> new LinkedList<>());
            rounds.add(new String[]{userMessage, response});
            while (rounds.size() > MAX_HISTORY_ROUNDS) {
                rounds.removeFirst();
            }
        }
    }
}
