package com.akimi;

import org.apache.lucene.analysis.*;
import org.apache.lucene.analysis.core.KeywordAnalyzer;
import org.apache.lucene.analysis.core.WhitespaceTokenizer;
import org.apache.lucene.analysis.miscellaneous.PerFieldAnalyzerWrapper;
import org.apache.lucene.analysis.ngram.EdgeNGramTokenFilter;
import org.apache.lucene.document.*;
import org.apache.lucene.index.*;
import org.apache.lucene.search.*;

import java.io.IOException;
import java.util.*;
import java.util.function.Consumer;

public class EmailAddressIndex {
    private final BaseLuceneIndex index;
    private final Analyzer analyzer;

    public EmailAddressIndex(BaseLuceneIndex index) {
        this.index = index;

        Analyzer addressSearchAnalyzer = new Analyzer() {
            @Override
            protected TokenStreamComponents createComponents(String fieldName) {
                Tokenizer source = new WhitespaceTokenizer();
                TokenStream filter = new LowerCaseFilter(source);
                filter = new EdgeNGramTokenFilter(filter, 2, 20, true);
                return new TokenStreamComponents(source, filter);
            }
        };

        // Apply n-grams ONLY to the "address_search" field
        this.analyzer = new PerFieldAnalyzerWrapper(
            new KeywordAnalyzer(),
            Map.of("address_search", addressSearchAnalyzer)
        );
    }

    public void start() throws IOException {
        index.init(new IndexWriterConfig(analyzer));
    }

    public void update(String address, boolean immediate) throws IOException,
        InterruptedException {
        // Exact term match on the StringField works perfectly now
        index.updateDocument(document(address), immediate,
            new Term("address", address));
    }

    public void add(String address, boolean immediate) throws IOException, InterruptedException {
        index.addDocument(document(address), immediate);
    }

    public static Document document(String address) {
        Document doc = new Document();
        // "address" is stored exactly for retrieval, updates, and deletes
        doc.add(new StringField("address", address, Field.Store.YES));
        // "address_search" gets tokenized into n-grams for autocomplete queries
        doc.add(new TextField("address_search", address, Field.Store.NO));
        return doc;
    }

    public void delete(String address) throws IOException {
        index.deleteDocument(new Term("address", address));
    }

    public List<String> autocompleteAddress(String address, int limit) throws IOException {
        // Use TermQuery against the n-gram optimized field
        Query query = new TermQuery(new Term("address_search", address.toLowerCase().trim()));
        List<String> results = new ArrayList<>();

        SearcherManager manager = index.getSearcherManager();
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

    public void startReload(Consumer<BaseLuceneIndex.LuceneEngine> rebuilder) {
        index.startReload(rebuilder, new IndexWriterConfig(analyzer));
    }

    public void commit() throws IOException {
        index.commit();
    }
}
