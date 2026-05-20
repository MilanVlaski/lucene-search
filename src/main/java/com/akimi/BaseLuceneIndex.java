package com.akimi;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.search.*;
import org.apache.lucene.store.FSDirectory;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Properties;

/*
    Holds an IndexWriter for persistent reads.
 */
public class BaseLuceneIndex {
    public static final DateTimeFormatter DIRECTORY_FORMAT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd.HH.mm.ss");

    private final Path rootDir;
    private final Path iniPath;

    private IndexWriter writer;
    private SearcherManager searcherManager;
    private ControlledRealTimeReopenThread<IndexSearcher> nrtThread;

    public BaseLuceneIndex(Path rootDir) {
        this.rootDir = rootDir;
        this.iniPath = rootDir.resolve("index.ini");
    }

    private record EngineTriple(
        IndexWriter writer,
        SearcherManager searcherManager,
        ControlledRealTimeReopenThread<IndexSearcher> nrtThread
    ) {}

    public synchronized void init(Analyzer analyzer) throws IOException {
        String currentDirName = readCurrentDirectoryName();
        if(currentDirName.isBlank()) {
            writeCurrentDirectoryName(LocalDateTime.now().format(BaseLuceneIndex.DIRECTORY_FORMAT));
        }
        currentDirName = readCurrentDirectoryName();

        assert currentDirName != null;
        Path indexPath = rootDir.resolve(currentDirName);

        Files.createDirectories(indexPath);


        EngineTriple triple = createEngineTriple(indexPath, analyzer);
        this.writer = triple.writer();
        this.searcherManager = triple.searcherManager();
        this.nrtThread = triple.nrtThread();
    }

    private EngineTriple createEngineTriple(Path indexPath, Analyzer analyzer) throws IOException {
        IndexWriter newWriter = new IndexWriter(FSDirectory.open(indexPath), new IndexWriterConfig(analyzer));
        SearcherManager newSearcherManager = new SearcherManager(newWriter, true, true, null);

        var newNrtThread = new ControlledRealTimeReopenThread<>(newWriter, newSearcherManager, 5.0, 0.025);
        newNrtThread.setName("Lucene-NRT-Thread-" + rootDir.getFileName() + "-" + indexPath.getFileName());
        newNrtThread.setDaemon(true);
        newNrtThread.start();

        return new EngineTriple(newWriter, newSearcherManager, newNrtThread);
    }

    // This is basically a utility
    public IndexWriter createTemporaryRebuildWriter(String timestamp,
                                                    Analyzer analyzer) throws IOException {
        Path newPath = rootDir.resolve(timestamp);
        IndexWriterConfig config = new IndexWriterConfig(analyzer);
        return new IndexWriter(FSDirectory.open(newPath), config);
    }

    public synchronized void switchToNewIndex(String timestamp, Analyzer analyzer) throws IOException {
        Path indexPath = rootDir.resolve(timestamp);
        Files.createDirectories(indexPath);

        // 1. Build the new triple in complete isolation
        EngineTriple newTriple = createEngineTriple(indexPath, analyzer);

        // 2. Capture the old references for cleanup
        IndexWriter oldWriter = this.writer;
        SearcherManager oldSearcherManager = this.searcherManager;
        ControlledRealTimeReopenThread<IndexSearcher> oldNrtThread = this.nrtThread;

        // 3. Instant atomic swap
        this.writer = newTriple.writer();
        this.searcherManager = newTriple.searcherManager();
        this.nrtThread = newTriple.nrtThread();

        writeCurrentDirectoryName(timestamp);

        // 4. Safe background deallocation of old resources
        if (oldNrtThread != null) {
            oldNrtThread.close();
            oldNrtThread.interrupt();
        }
        if (oldSearcherManager != null) {
            oldSearcherManager.close();
        }
        if (oldWriter != null) {
            var oldDir = oldWriter.getDirectory();
            oldWriter.close();
            if (oldDir != null) {
                oldDir.close();
            }
        }
    }

    private String readCurrentDirectoryName() throws IOException {
        if (Files.notExists(iniPath)) {
            return null;
        }

        Properties props = new Properties();
        try (InputStream is = Files.newInputStream(iniPath)) {
            props.load(is);
        }

        return props.getProperty("current");
    }

    private void writeCurrentDirectoryName(String timestamp) throws IOException {
        Properties props = new Properties();
        props.setProperty("current", timestamp);
        try (OutputStream os = Files.newOutputStream(iniPath)) {
            props.store(os, null);
        }
    }


    public IndexWriter getWriter() {
        return writer;
    }

    public SearcherManager getSearcherManager() {
        return searcherManager;
    }

    public ControlledRealTimeReopenThread<IndexSearcher> getNrtThread() {
        return nrtThread;
    }
}
