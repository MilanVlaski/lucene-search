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
    private final BaseLuceneIndex storage;
    private final Analyzer analyzer;

    public EmailAddressIndex(BaseLuceneIndex storage) {
        this.storage = storage;

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
        storage.init(new IndexWriterConfig(analyzer));
    }

    public void update(String address, boolean immediate) throws IOException,
        InterruptedException {
        // Exact term match on the StringField works perfectly now
        storage.updateDocument(document(address), false,
            new Term("address", address));
    }

    public void add(String address, boolean immediate) throws IOException, InterruptedException {
        storage.addDocument(document(address), immediate);
    }

    public static Document document(String address) {
        Document doc = new Document();
        // "address" is stored exactly for retrieval, updates, and deletes
        doc.add(new StringField("address", address, Field.Store.YES));
        // "address_search" gets tokenized into n-grams for autocomplete queries
        doc.add(new TextField("address_search", address, Field.Store.NO));
        return doc;
    }

    // TODO control shouldn't leave the base class
    // Only search may be outside the base class!
    // Write operations gotta be controlled by the base class
    // TODO queue deletes and adds if we're currently rebuilding
    public void delete(String address) throws IOException {
        storage.deleteDocument(new Term("address", address));
    }

    public List<String> autocompleteAddress(String address, int limit) throws IOException {
        // Use TermQuery against the n-gram optimized field
        Query query = new TermQuery(new Term("address_search", address.toLowerCase().trim()));
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

    public void startReload(Consumer<BaseLuceneIndex.EngineTriple> rebuilder) {
        storage.startReload(rebuilder, new IndexWriterConfig(analyzer));
    }
}
