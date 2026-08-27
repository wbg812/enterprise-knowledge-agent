package com.chenghao.study.knowledgeagent.dto;

import dev.langchain4j.data.segment.TextSegment;
import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 混合检索命中结果：文档片段 + 融合排序得分（RRF）
 */
@Data
@AllArgsConstructor
public class SearchHit {

    /** 检索命中的文档片段 */
    private TextSegment segment;

    /** 融合排序得分（RRF 值，仅用于排序与展示） */
    private double score;
}
