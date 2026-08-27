package com.chenghao.study.knowledgeagent.service;

import com.chenghao.study.knowledgeagent.dto.SearchHit;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 混合检索服务：向量语义召回 + 中文关键词召回，RRF 融合重排序。
 * <p>
 * 纯向量检索对专有名词（人名、产品名、缩写）容易漏召回，
 * 叠加关键词通道后用 RRF（Reciprocal Rank Fusion）融合两路排名，兼顾语义与精确匹配。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SearchService {

    /** RRF 常数，取值越小越放大排名靠前的权重，常用 60 */
    private static final double RRF_K = 60.0;

    private final EmbeddingModel embeddingModel;
    private final InMemoryEmbeddingStore<TextSegment> embeddingStore;

    /** 每路召回的候选数量（重排前初筛池） */
    @Value("${knowledge.agent.candidate-size:20}")
    private int candidateSize;

    /** 关键词全文索引（与向量库同源，文档向量化时同步登记） */
    private final List<TextSegment> keywordIndex = new CopyOnWriteArrayList<>();

    /** 登记新文档的片段到关键词索引 */
    public void registerSegments(List<TextSegment> segments) {
        keywordIndex.addAll(segments);
    }

    /** 清空关键词索引（重建向量库时同步调用） */
    public void clearIndex() {
        keywordIndex.clear();
    }

    /**
     * 混合检索：双路召回后按 RRF 融合重排，返回归一化得分的 Top-K 结果
     */
    public List<SearchHit> hybridSearch(String query, int topK) {
        Embedding queryEmbedding = embeddingModel.embed(query).content();

        List<TextSegment> vectorCandidates = vectorSearch(queryEmbedding);
        List<TextSegment> keywordCandidates = keywordSearch(query);

        List<SearchHit> hits = rrfFuse(vectorCandidates, keywordCandidates, topK);
        log.info("混合检索「{}」：向量候选 {} 个，关键词候选 {} 个，融合后取 {} 个",
                query, vectorCandidates.size(), keywordCandidates.size(), hits.size());
        return hits;
    }

    /**
     * 关键词全量召回（统计类问题专用）：返回所有命中片段，不做 top-k 限制
     * 用于"多少人/多少部门/总数"这类需要看到完整数据的查询
     */
    public List<TextSegment> keywordAllSearch(String query) {
        List<String> queryTerms = bigrams(query);
        if (queryTerms.isEmpty()) {
            return Collections.emptyList();
        }

        List<TextSegment> results = new ArrayList<>();
        for (TextSegment segment : keywordIndex) {
            String text = segment.text();
            for (String term : queryTerms) {
                if (text.contains(term)) {
                    results.add(segment);
                    break; // 一个片段命中多个 term 只记录一次
                }
            }
        }
        log.info("关键词全量召回「{}」：命中 {} 个片段", query, results.size());
        return results;
    }

    /** 判断问题是否属于统计类（包含计数/统计关键词） */
    public static boolean isStatisticalQuery(String query) {
        String lower = query.toLowerCase();
        return lower.contains("多少") || lower.contains("几个") || lower.contains("人数")
                || lower.contains("总数") || lower.contains("总计") || lower.contains("共有")
                || lower.contains("统计") || lower.contains("count") || lower.contains("how many");
    }

    /** 向量语义召回 */
    private List<TextSegment> vectorSearch(Embedding queryEmbedding) {
        EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                .queryEmbedding(queryEmbedding)
                .maxResults(candidateSize)
                .minScore(0.0)
                .build();
        EmbeddingSearchResult<TextSegment> result = embeddingStore.search(request);

        List<TextSegment> segments = new ArrayList<>();
        for (EmbeddingMatch<TextSegment> match : result.matches()) {
            segments.add(match.embedded());
        }
        return segments;
    }

    /** 关键词召回：字符二元组覆盖率打分（无需分词器，对中英混合文本均有效） */
    private List<TextSegment> keywordSearch(String query) {
        List<String> queryTerms = bigrams(query);
        if (queryTerms.isEmpty()) {
            return Collections.emptyList();
        }

        List<Map.Entry<TextSegment, Double>> scored = new ArrayList<>();
        for (TextSegment segment : keywordIndex) {
            String text = segment.text();
            int hitCount = 0;
            for (String term : queryTerms) {
                if (text.contains(term)) {
                    hitCount++;
                }
            }
            if (hitCount > 0) {
                scored.add(new AbstractMap.SimpleEntry<>(segment, (double) hitCount / queryTerms.size()));
            }
        }
        scored.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));

        List<TextSegment> top = new ArrayList<>();
        for (int i = 0; i < scored.size() && i < candidateSize; i++) {
            top.add(scored.get(i).getKey());
        }
        return top;
    }

    /** RRF 融合重排：score(d) = Σ 1/(k + rank)，只依赖各路排名不依赖分数尺度 */
    private List<SearchHit> rrfFuse(List<TextSegment> vectorCandidates,
                                    List<TextSegment> keywordCandidates,
                                    int topK) {
        Map<TextSegment, Double> scores = new LinkedHashMap<>();
        addRrfScores(scores, vectorCandidates);
        addRrfScores(scores, keywordCandidates);

        List<Map.Entry<TextSegment, Double>> sorted = new ArrayList<>(scores.entrySet());
        sorted.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));

        double maxScore = sorted.isEmpty() ? 1.0 : sorted.get(0).getValue();
        List<SearchHit> hits = new ArrayList<>();
        for (int i = 0; i < sorted.size() && i < topK; i++) {
            Map.Entry<TextSegment, Double> entry = sorted.get(i);
            // 归一化：最高分 = 1.0，便于前端展示相关度
            hits.add(new SearchHit(entry.getKey(), entry.getValue() / maxScore));
        }
        return hits;
    }

    private void addRrfScores(Map<TextSegment, Double> scores, List<TextSegment> rankedList) {
        for (int rank = 0; rank < rankedList.size(); rank++) {
            scores.merge(rankedList.get(rank), 1.0 / (RRF_K + rank + 1), Double::sum);
        }
    }

    /** 切分字符二元组（先剔除空白与标点），如"市场部"→["市场","政部"] */
    static List<String> bigrams(String text) {
        StringBuilder cleaned = new StringBuilder();
        for (char c : text.toCharArray()) {
            if (Character.isLetterOrDigit(c)) {
                cleaned.append(c);
            }
        }

        String s = cleaned.toString();
        List<String> terms = new ArrayList<>();
        if (s.length() == 1) {
            terms.add(s);
            return terms;
        }
        for (int i = 0; i < s.length() - 1; i++) {
            terms.add(s.substring(i, i + 2));
        }
        return terms;
    }
}
