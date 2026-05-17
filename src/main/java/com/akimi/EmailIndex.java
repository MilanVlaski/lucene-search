package com.akimi;

import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.store.FSDirectory;

import java.io.IOException;
import java.nio.file.Paths;

public class EmailIndex {
    private final BaseLuceneIndex index;
    private final IndexWriter writer;
    private final StandardAnalyzer analyzer;

    public EmailIndex(FSDirectory dir, BaseLuceneIndex index) throws IOException {
        this.index = index;
        this.analyzer = new StandardAnalyzer();
        var config = new IndexWriterConfig(analyzer);
        config.setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND);
        this.writer = new IndexWriter(dir, config);
    }

    public void search() {
//        index.searcher.search()
    }

    public void addToIndex() {
//        writer.addDocument()
    }

    public void deleteFromIndex() {
//        writer.deleteDocuments()
    }

    public void rebuildIndexFrom(String bla) {
        try {
            var newWriter = new IndexWriter(FSDirectory.open(Paths.get("bla")),
                new IndexWriterConfig(analyzer)
            );
//            newWriter.addDocuments()
            index.swapToNewIndex(bla);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

    }
}
