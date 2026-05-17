package com.akimi;

import org.apache.lucene.index.*;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;

import java.io.IOException;
import java.nio.file.Paths;

public class BaseLuceneIndex {

    // Volatile ensures visibility across query threads when the reference changes
    public volatile IndexSearcher searcher;
    private Directory directory;

    public BaseLuceneIndex(FSDirectory directory) throws IOException {
        this.directory = directory;
        var reader = DirectoryReader.open(this.directory);
        this.searcher = new IndexSearcher(reader);
    }

    /**
     * Atomically switches the application to a freshly built index directory.
     */
    public synchronized void swapToNewIndex(String newIndexPath) throws IOException {
        // 1. Open the new directory and reader
        var nextDir = FSDirectory.open(Paths.get(newIndexPath));
        var nextReader = DirectoryReader.open(nextDir);
        var nextSearcher = new IndexSearcher(nextReader);

        // 2. Warm up the new searcher (Crucial to populate OS page cache / Lucene caches)
        warmUp(nextSearcher);

        // 3. Keep references to the old components for cleanup
        var oldSearcher = this.searcher;
        var oldDir = this.directory;

        // 4. The Atomic Swap
        // New incoming queries via getSearcher() instantly use the new index
        this.searcher = nextSearcher;
        this.directory = nextDir;

        // 5. Resource Cleanup
        // Safely close the old reader. Existing queries running on oldSearcher
        // will finish safely because Lucene readers use ref-counting under the hood.
        oldSearcher.getIndexReader().close();
        oldDir.close();
    }

    private void warmUp(IndexSearcher searcher) {
        try {
            // Run a few common heavy queries here so the new index isn't "cold"
            // e.g., searcher.search(someCommonQuery, 10);
        } catch (Exception e) {
            // Log warning but don't block the swap
        }
    }
}
