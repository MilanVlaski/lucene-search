package com.akimi;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.*;
import org.apache.lucene.index.*;
import org.apache.lucene.search.*;

import java.io.IOException;
import java.util.function.Consumer;

public class EmailIndex {
    private final BaseLuceneIndex storage;
    private final Analyzer analyzer;

    public EmailIndex(BaseLuceneIndex storage) {
        this.storage = storage;
        this.analyzer = new StandardAnalyzer();
    }

    public void start() throws IOException {
        storage.init(new IndexWriterConfig(analyzer));
    }

    public void add(String id, String body, boolean immediate) throws IOException, InterruptedException {
        Document doc = document(id, body);
        storage.addDocument(doc, immediate);
    }

    public static Document document(String id, String body) {
        Document doc = new Document();
        doc.add(new StringField("id", id, Field.Store.YES));
        doc.add(new TextField("body", body, Field.Store.NO));
        return doc;
    }

    public void delete(String id) throws IOException {
        storage.deleteDocument(new Term("id", id));
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

    public void startReload(Consumer<BaseLuceneIndex.EngineTriple> rebuilder) {
        storage.startReload(rebuilder, new IndexWriterConfig(analyzer));
    }

}
