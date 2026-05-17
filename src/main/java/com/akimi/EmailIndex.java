package com.akimi;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.*;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.*;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

public class EmailIndex {
    private final BaseLuceneIndex storage;
    private final Analyzer analyzer;

    public EmailIndex(BaseLuceneIndex storage) {
        this.storage = storage;
        this.analyzer = new StandardAnalyzer();
    }

    public void start() throws IOException {
        storage.init(analyzer);
    }

    // Perhaps refactor into two methods?
    public void add(String id, String body, boolean immediate) throws IOException, InterruptedException {
        Document doc = new Document();
        doc.add(new StringField("id", id, Field.Store.YES));
        doc.add(new TextField("body", body, Field.Store.NO));

        long gen = storage.getWriter().addDocument(doc);
        if (immediate) {
            storage.getNrtThread().waitForGeneration(gen);
        }
    }

    public void delete(String id) throws IOException {
        storage.getWriter().deleteDocuments(new Term("id", id));
    }

    public TopDocs search(Query query, int limit) throws IOException {
        SearcherManager manager = storage.getSearcherManager();
        IndexSearcher searcher = manager.acquire();
        try {
            return searcher.search(query, limit);
        } finally {
            manager.release(searcher);
        }
    }

    public void rebuildIndex(List<Document> documents) throws IOException {
        String newTimestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd.HH.mm.ss"));

        // 1. Create isolated writer using the EmailIndexer's specific analyzer
        try (IndexWriter rebuildWriter = storage.createTemporaryRebuildWriter(newTimestamp, analyzer)) {

            rebuildWriter.addDocuments(documents);
            rebuildWriter.commit();

            // 3. Atomically drop old readers/writers and update index.ini
            storage.switchToNewIndex(newTimestamp, analyzer);
        }
    }
}
