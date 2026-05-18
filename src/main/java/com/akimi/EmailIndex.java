package com.akimi;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.*;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.*;

import java.io.IOException;

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

    public void add(String id, String body, boolean immediate) throws IOException, InterruptedException {
        Document doc = document(id, body);

        long gen = storage.getWriter().addDocument(doc);
        if (immediate) {
            storage.getNrtThread().waitForGeneration(gen);
        }
    }

    public static Document document(String id, String body) {
        Document doc = new Document();
        doc.add(new StringField("id", id, Field.Store.YES));
        doc.add(new TextField("body", body, Field.Store.NO));
        return doc;
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

    public IndexWriter createTemporaryRebuildWriter(String timestamp) throws IOException {
        return storage.createTemporaryRebuildWriter(timestamp, analyzer);
    }

    public void switchToNewIndex(String timestamp) throws IOException {
        storage.switchToNewIndex(timestamp, analyzer);
    }

}
