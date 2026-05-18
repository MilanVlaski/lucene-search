package com.akimi;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.core.KeywordAnalyzer;
import org.apache.lucene.document.*;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.*;

import java.io.IOException;
import java.util.ArrayList;
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

    public void addOrUpdate(String address, boolean immediate) throws IOException, InterruptedException {
        var doc = document(address);
        long gen = storage.getWriter().updateDocument(new Term("address", address), doc);
        if (immediate) {
            storage.getNrtThread().waitForGeneration(gen);
        }
    }

    public void add(String address, boolean immediate) throws IOException, InterruptedException {
        Document doc = document(address);

        long gen = storage.getWriter().addDocument(doc);
        if (immediate) {
            storage.getNrtThread().waitForGeneration(gen);
        }
    }

    public static Document document(String address) {
        Document doc = new Document();
        doc.add(new StringField("address", address, Field.Store.YES));
        return doc;
    }

    public void delete(String address) throws IOException {
        storage.getWriter().deleteDocuments(new Term("address", address));
    }

    public List<String> autocompleteAddress(String prefix, int limit) throws IOException {
        Query query = new PrefixQuery(new Term("address", prefix.toLowerCase()));
        List<String> results = new ArrayList<>();

        SearcherManager manager = storage.getSearcherManager();

        IndexSearcher searcher = manager.acquire();
        try {
            TopDocs hits = searcher.search(query, limit);

            for (ScoreDoc scoreDoc : hits.scoreDocs) {
                Document doc = searcher.storedFields().document(scoreDoc.doc);
                results.add(doc.get("address"));
            }
        } finally {
            manager.release(searcher);
        }

        return results;
    }

    public IndexWriter createTemporaryRebuildWriter(String timestamp) throws IOException {
        return storage.createTemporaryRebuildWriter(timestamp, analyzer);
    }

    public void switchToNewIndex(String timestamp) throws IOException {
        storage.switchToNewIndex(timestamp, analyzer);
    }
}
