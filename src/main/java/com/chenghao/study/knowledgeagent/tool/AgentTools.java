package com.chenghao.study.knowledgeagent.tool;

import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 智能体工具集：提供日期查询、数学计算等通用能力。
 * <p>
 * 每个 @Tool 方法会被 LangChain4j 自动注册为工具规范，
 * AI 可以根据用户问题自主决定调用哪个工具。
 */
@Slf4j
public class AgentTools {

    /**
     * 获取当前日期和时间
     */
    @Tool(name = "getCurrentDateTime", value = "获取当前的日期和时间，当用户询问今天几号、星期几、现在几点时使用")
    public String getCurrentDateTime() {
        LocalDateTime now = LocalDateTime.now();
        String result = now.format(DateTimeFormatter.ofPattern("yyyy年MM月dd日 HH:mm:ss"));
        log.info("工具调用 [getCurrentDateTime] → {}", result);
        return result;
    }

    /**
     * 数学计算：支持加减乘除、括号、常见数学运算
     */
    @Tool(name = "calculate", value = "执行数学计算，当用户询问算术题、百分比、平均值、总和等计算问题时使用。" +
            "参数 expression 为数学表达式字符串，如 '100 * 1.05' 或 '(200 + 300) / 5'")
    public String calculate(String expression) {
        log.info("工具调用 [calculate] 表达式：{}", expression);
        try {
            double result = evaluateExpression(expression);
            String resultStr;
            if (result == Math.floor(result) && !Double.isInfinite(result)) {
                resultStr = String.valueOf((long) result);
            } else {
                resultStr = String.valueOf(result);
            }
            log.info("工具调用 [calculate] {} = {}", expression, resultStr);
            return expression + " = " + resultStr;
        } catch (Exception e) {
            log.warn("计算失败：{}，错误：{}", expression, e.getMessage());
            return "计算失败：" + e.getMessage();
        }
    }

    /**
     * 简易表达式求值器（支持 +、-、*、/、括号）
     */
    private static double evaluateExpression(String expr) {
        // 去除空白
        expr = expr.replaceAll("\\s+", "");
        // 替换中文符号
        expr = expr.replace("×", "*").replace("÷", "/").replace("（", "(").replace("）", ")");
        return parseExpression(expr, new int[]{0});
    }

    private static double parseExpression(String s, int[] pos) {
        double result = parseTerm(s, pos);
        while (pos[0] < s.length()) {
            char op = s.charAt(pos[0]);
            if (op == '+' || op == '-') {
                pos[0]++;
                double term = parseTerm(s, pos);
                result = (op == '+') ? result + term : result - term;
            } else {
                break;
            }
        }
        return result;
    }

    private static double parseTerm(String s, int[] pos) {
        double result = parseFactor(s, pos);
        while (pos[0] < s.length()) {
            char op = s.charAt(pos[0]);
            if (op == '*' || op == '/') {
                pos[0]++;
                double factor = parseFactor(s, pos);
                if (op == '*') {
                    result *= factor;
                } else {
                    if (factor == 0) throw new ArithmeticException("除数不能为零");
                    result /= factor;
                }
            } else {
                break;
            }
        }
        return result;
    }

    private static double parseFactor(String s, int[] pos) {
        // 处理正负号
        if (pos[0] < s.length() && (s.charAt(pos[0]) == '-' || s.charAt(pos[0]) == '+')) {
            char sign = s.charAt(pos[0]);
            pos[0]++;
            double factor = parseFactor(s, pos);
            return sign == '-' ? -factor : factor;
        }

        // 处理括号
        if (pos[0] < s.length() && s.charAt(pos[0]) == '(') {
            pos[0]++; // skip '('
            double result = parseExpression(s, pos);
            if (pos[0] < s.length() && s.charAt(pos[0]) == ')') {
                pos[0]++; // skip ')'
            }
            return result;
        }

        // 解析数字
        int start = pos[0];
        while (pos[0] < s.length() && (Character.isDigit(s.charAt(pos[0])) || s.charAt(pos[0]) == '.')) {
            pos[0]++;
        }
        if (start == pos[0]) {
            throw new IllegalArgumentException("无效表达式，位置 " + pos[0] + " 处无法解析");
        }
        return Double.parseDouble(s.substring(start, pos[0]));
    }
}
