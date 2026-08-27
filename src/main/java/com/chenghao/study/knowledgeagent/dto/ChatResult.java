package com.chenghao.study.knowledgeagent.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

/**
 * 问答结果：答案 + 引用来源
 */
@Data
@AllArgsConstructor
public class ChatResult {

    /** 大模型生成的回答 */
    private String answer;

    /** 引用的文档来源（按相关性去重） */
    private List<Source> sources;

    @Data
    @AllArgsConstructor
    public static class Source {
        /** 来源文档文件名 */
        private String fileName;
        /** 相似度得分 */
        private double score;
    }
}
