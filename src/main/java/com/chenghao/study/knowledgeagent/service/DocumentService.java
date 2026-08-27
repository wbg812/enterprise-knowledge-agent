package com.chenghao.study.knowledgeagent.service;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.loader.FileSystemDocumentLoader;
import dev.langchain4j.data.document.parser.apache.poi.ApachePoiDocumentParser;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

@Slf4j
@Service
public class DocumentService implements CommandLineRunner {

    @Autowired
    private EmbeddingModel embeddingModel;

    @Autowired
    private InMemoryEmbeddingStore<TextSegment> embeddingStore;

    @Autowired
    private SearchService searchService;

    @Value("${knowledge.agent.docs.input-path:docs/input}")
    private String inputPath;

    @Value("${knowledge.agent.chunk-size:200}")
    private int chunkSize;

    @Value("${knowledge.agent.chunk-overlap:50}")
    private int chunkOverlap;

    /** 已向量化的文件名集合，避免上传新文档时重复向量化旧文档 */
    private final Set<String> ingestedFiles = new HashSet<>();

    private int processedCount = 0;

    @Override
    public void run(String... args) {
        ingestNewDocuments();
    }

    /**
     * 加载输入目录中的文档，仅对新增文档建立向量索引
     */
    public void ingestNewDocuments() {
        List<Document> newDocuments = loadNewDocuments();
        if (!newDocuments.isEmpty()) {
            ingestDocuments(newDocuments);
        }
    }

    /**
     * 扫描输入目录，加载尚未向量化的文档
     */
    private List<Document> loadNewDocuments() {
        List<Document> documents = new ArrayList<>();
        Path path = Paths.get(inputPath);

        if (!Files.exists(path)) {
            log.warn("输入目录不存在：{}", inputPath);
            return documents;
        }

        try (Stream<Path> paths = Files.walk(path)) {
            paths.filter(Files::isRegularFile)
                    .filter(DocumentService::isSupportedFile)
                    .forEach(p -> {
                        String fileName = p.getFileName().toString();
                        if (ingestedFiles.contains(fileName)) {
                            return; // 跳过已向量化文档
                        }
                        try {
                            // Excel/Word 使用 POI 解析器，PDF/TXT 使用默认解析器
                            Document doc = isOfficeFile(fileName)
                                    ? FileSystemDocumentLoader.loadDocument(p, new ApachePoiDocumentParser())
                                    : FileSystemDocumentLoader.loadDocument(p);
                            documents.add(doc);
                            log.info("加载文档：{}", fileName);
                        } catch (Exception e) {
                            log.error("加载文档失败：{}", fileName, e);
                        }
                    });
        } catch (IOException e) {
            log.error("读取文档目录失败", e);
        }

        return documents;
    }

    /**
     * 处理文档：分片、向量化、存储，并同步登记关键词索引（混合检索用）
     */
    private void ingestDocuments(List<Document> documents) {
        DocumentSplitter splitter = DocumentSplitters.recursive(chunkSize, chunkOverlap);

        for (Document doc : documents) {
            String fileName = doc.metadata("file_name");
            // 切分 → 向量化 → 写入向量库，片段同步登记到关键词索引（两库同源）
            List<TextSegment> segments = splitter.split(doc);
            List<Embedding> embeddings = embeddingModel.embedAll(segments).content();
            embeddingStore.addAll(embeddings, segments);
            searchService.registerSegments(segments);
            if (fileName != null) {
                ingestedFiles.add(fileName);
            }
            processedCount++;
            log.info("文档处理完成：{}（共 {} 个片段）", fileName, segments.size());
        }

        log.info("本批向量化 {} 个文档，累计处理 {} 个文档", documents.size(), processedCount);
    }

    /**
     * 重建向量索引：清空现有向量后重新向量化全部文档
     * 用于删除文档后清理残留向量
     */
    public void rebuildIndex() {
        embeddingStore.removeAll();
        searchService.clearIndex();
        ingestedFiles.clear();
        processedCount = 0;
        ingestNewDocuments();
        log.info("向量索引重建完成");
    }

    public int getProcessedCount() {
        return processedCount;
    }

    /** 是否支持的文件格式（大小写不敏感） */
    private static boolean isSupportedFile(Path path) {
        String name = path.toString().toLowerCase();
        return name.endsWith(".pdf") || name.endsWith(".txt") || name.endsWith(".csv")
                || name.endsWith(".xlsx") || name.endsWith(".xls")
                || name.endsWith(".doc") || name.endsWith(".docx");
    }

    /** 是否需要 Apache POI 解析的 Office 文档 */
    private static boolean isOfficeFile(String fileName) {
        String name = fileName.toLowerCase();
        return name.endsWith(".xlsx") || name.endsWith(".xls")
                || name.endsWith(".doc") || name.endsWith(".docx");
    }
}
