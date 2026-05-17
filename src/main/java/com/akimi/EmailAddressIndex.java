package com.akimi;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.core.KeywordAnalyzer;
import org.apache.lucene.document.*;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.*;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

public class EmailAddressIndex {
    private final BaseLuceneIndex storage;
    private final Analyzer analyzer;

    public EmailAddressIndex(BaseLuceneIndex storage) {
        this.storage = storage;
        this.analyzer = new KeywordAnalyzer();
    }

    public void start() throws IOException {
        storage.init(analyzer);
    }

    public void add(String address, boolean immediate) throws IOException, InterruptedException {
        Document doc = new Document();
        doc.add(new StringField("address", address, Field.Store.YES));

        long gen = storage.getWriter().addDocument(doc);
        if (immediate) {
            storage.getNrtThread().waitForGeneration(gen);
        }
    }

    public void delete(String address) throws IOException {
        storage.getWriter().deleteDocuments(new Term("address", address));
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

    // TODO
    public void rebuildIndex(List<Document> documents) throws IOException {
        String newTimestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd.HH.mm.ss"));

        // 1. Create isolated writer using the EmailIndexer's specific analyzer
        try (IndexWriter rebuildWriter = storage.createTemporaryRebuildWriter(newTimestamp, analyzer)) {

            rebuildWriter.addDocuments(documents);
            rebuildWriter.commit();
        }

        // 3. Atomically drop old readers/writers and update index.ini
        storage.switchToNewIndex(newTimestamp, analyzer);
    }

}
