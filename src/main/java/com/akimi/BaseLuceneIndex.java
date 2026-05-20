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

    public synchronized void init(Analyzer analyzer) throws IOException {
        String currentDirName = readCurrentDirectoryName();
        if(currentDirName.isBlank()) {
            writeCurrentDirectoryName(LocalDateTime.now().format(BaseLuceneIndex.DIRECTORY_FORMAT));
        }
        currentDirName = readCurrentDirectoryName();

        Path indexPath = rootDir.resolve(currentDirName);

        Files.createDirectories(indexPath);

        // Open an un-locked FSDirectory
        var dir = FSDirectory.open(indexPath);
        var config = new IndexWriterConfig(analyzer);

        this.writer = new IndexWriter(dir, config);
        this.searcherManager = new SearcherManager(writer, true, true, null);

        this.nrtThread = new ControlledRealTimeReopenThread<>(writer, searcherManager, 5.0, 0.025);
        this.nrtThread.setName("Lucene-NRT-Thread-" + rootDir.getFileName());
        this.nrtThread.setDaemon(true);
        this.nrtThread.start();
    }

    // This is basically a utility
    public IndexWriter createTemporaryRebuildWriter(String timestamp,
                                                    Analyzer analyzer) throws IOException {
        Path newPath = rootDir.resolve(timestamp);
        IndexWriterConfig config = new IndexWriterConfig(analyzer);
        return new IndexWriter(FSDirectory.open(newPath), config);
    }

    public synchronized void switchToNewIndex(String timestamp, Analyzer analyzer) throws IOException {
        // 1. Stop the background refresh thread first
        if (nrtThread != null) {
            nrtThread.close();
            nrtThread.interrupt();
        }
        // 2. Release readers
        if (searcherManager != null) {
            searcherManager.close();
        }
        // 3. Commit and close the old disk writer
        if (writer != null) {
            writer.close();
        }

        writeCurrentDirectoryName(timestamp);
        init(analyzer);
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
