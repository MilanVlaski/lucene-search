package com.akimi;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.search.*;
import org.apache.lucene.store.FSDirectory;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public class BaseLuceneIndex {
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
        Path indexPath = rootDir.resolve(currentDirName);

        // TODO meh
        if (!Files.exists(indexPath)) {
            Files.createDirectories(indexPath);
        }

        IndexWriterConfig config = new IndexWriterConfig(analyzer);
        this.writer = new IndexWriter(FSDirectory.open(indexPath), config);
        this.searcherManager = new SearcherManager(writer, true, true, null);

        // 5.0s max stale (passive), 0.025s min stale (burst protective)
        this.nrtThread = new ControlledRealTimeReopenThread<>(writer, searcherManager, 5.0, 0.025);
        this.nrtThread.setName("Lucene-NRT-Thread-" + rootDir.getFileName());
        this.nrtThread.setDaemon(true);
        this.nrtThread.start();
    }

    public IndexWriter createTemporaryRebuildWriter(String timestamp, Analyzer analyzer) throws IOException {
        Path newPath = rootDir.resolve(timestamp);
        Files.createDirectories(newPath);
        IndexWriterConfig config = new IndexWriterConfig(analyzer);
        return new IndexWriter(FSDirectory.open(newPath), config);
    }

    public synchronized void switchToNewIndex(String timestamp, Analyzer analyzer) throws IOException {
        if (nrtThread != null) nrtThread.close();
        if (searcherManager != null) searcherManager.close();
        if (writer != null) writer.close();

        writeCurrentDirectoryName(timestamp);
        init(analyzer);
    }

    private String readCurrentDirectoryName() throws IOException {
        if (!Files.exists(iniPath)) {
            return "default";
        }
        Properties props = new Properties();
        try (InputStream is = Files.newInputStream(iniPath)) {
            props.load(is);
        }
        return props.getProperty("current", "default");
    }

    private void writeCurrentDirectoryName(String timestamp) throws IOException {
        Properties props = new Properties();
        props.setProperty("current", timestamp);
        try (OutputStream os = Files.newOutputStream(iniPath)) {
            props.store(os, "Lucene Index Tracking");
        }
    }

    public IndexWriter getWriter() { return writer; }
    public SearcherManager getSearcherManager() { return searcherManager; }
    public ControlledRealTimeReopenThread<IndexSearcher> getNrtThread() { return nrtThread; }
}
