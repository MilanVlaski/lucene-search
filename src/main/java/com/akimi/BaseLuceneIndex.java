package com.akimi;

import org.apache.lucene.document.Document;
import org.apache.lucene.index.*;
import org.apache.lucene.search.*;
import org.apache.lucene.store.FSDirectory;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Consumer;

public class BaseLuceneIndex {
    public static final DateTimeFormatter DIRECTORY_FORMAT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd.HH.mm.ss");

    private final Path rootDir;
    private final Path iniPath;

    private volatile LuceneEngine engine;
    private volatile RebuildQueue rebuildQueue;

    public BaseLuceneIndex(Path rootDir) {
        this.rootDir = rootDir;
        this.iniPath = rootDir.resolve("index.ini");
    }

    public record LuceneEngine(
        IndexWriter writer,
        SearcherManager searcherManager,
        ControlledRealTimeReopenThread<IndexSearcher> nrtThread
    ) implements Closeable {

        @Override
        public void close() throws IOException {
            if (nrtThread != null) {
                nrtThread.close();
                nrtThread.interrupt();
            }
            if (searcherManager != null) {
                searcherManager.close();
            }
            if (writer != null) {
                writer.close();
            }
        }
    }

    public record RebuildQueue(
        List<Document> addQueue,
        List<Term> deleteQueue,
        List<UpdateRec> updateQueue
    ) {}

    public record UpdateRec(
        Term term,
        Document doc
    ) {}


    public synchronized void init(IndexWriterConfig writerConfig) throws IOException {
        String currentDirName = readCurrentDirectoryName();
        if (currentDirName == null || currentDirName.isBlank()) {
            writeCurrentDirectoryName(LocalDateTime.now().format(BaseLuceneIndex.DIRECTORY_FORMAT));
        }
        currentDirName = readCurrentDirectoryName();

        assert currentDirName != null;
        Path indexPath = rootDir.resolve(currentDirName);

        Files.createDirectories(indexPath);


        this.engine = createEngineTriple(indexPath, writerConfig);
    }

    private LuceneEngine createEngineTriple(Path indexPath, IndexWriterConfig writerConfig) throws IOException {
        // Open the writer first
        IndexWriter newWriter = new IndexWriter(FSDirectory.open(indexPath), writerConfig);
        try {
            // Open the searcher manager next
            SearcherManager newSearcherManager = new SearcherManager(newWriter, true, true, null);
            try {
                ControlledRealTimeReopenThread<IndexSearcher> newNrtThread =
                    new ControlledRealTimeReopenThread<>(newWriter, newSearcherManager, 5.0, 0.025);

                newNrtThread.setName("Lucene-NRT-" + rootDir.getFileName());
                newNrtThread.setDaemon(true);
                newNrtThread.start();

                return new LuceneEngine(newWriter, newSearcherManager,
                    newNrtThread);

            } catch (Throwable t) {
                newSearcherManager.close();
                throw t;
            }
        } catch (Throwable t) {
            newWriter.close();
            throw t;
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

    public synchronized void addDocument(Document doc, boolean immediate) throws IOException, InterruptedException {
        // The rebuild engine is not safe. If it switched between these two
        // lines, we're in trouble. They must be synchronized, somehow.
        long gen = engine.writer().addDocument(doc);

        var queue = rebuildQueue;
        if (queue != null) {
            // Time capsule: stash it for later catch-up
            queue.addQueue().add(doc);
        }

        if (immediate) {
            engine.nrtThread().waitForGeneration(gen);
        }
    }

    public synchronized void updateDocument(Document doc, boolean immediate, Term term) throws IOException, InterruptedException {
        long gen = engine.writer().updateDocument(term, doc);

        var queue = rebuildQueue;
        if (queue != null) {
            queue.updateQueue().add(new UpdateRec(term, doc));
        }

        if (immediate) {
            engine.nrtThread().waitForGeneration(gen);
        }
    }

    public synchronized void deleteDocument(Term term) throws IOException {
        engine.writer().deleteDocuments(term);

        var queue = rebuildQueue;
        if (queue != null) {
            queue.deleteQueue().add(term);
        }
    }

    public void startReload(Consumer<LuceneEngine> rebuilder, IndexWriterConfig writerConfig) {
        LuceneEngine newEngine = null;
        String timestamp = LocalDateTime.now().format(DIRECTORY_FORMAT);
        Path indexPath = rootDir.resolve(timestamp);

        try {
            newEngine = createEngineTriple(indexPath, writerConfig);

            synchronized (this) {
                this.rebuildQueue = new RebuildQueue(new ArrayList<>(),
                    new ArrayList<>(), new ArrayList<>());
            }

            rebuilder.accept(newEngine);

            switchToNewIndex(timestamp, newEngine);
        } catch (Throwable t) {
            synchronized (this) {
                this.rebuildQueue = null;
            }
            if (newEngine != null) {
                try {
                    newEngine.close();
                } catch (IOException e) {
                    // Log or suppress
                }
            }
            throw new RuntimeException("Index reload failed", t);
        }
    }

    private synchronized void switchToNewIndex(String timestamp, LuceneEngine newEngine) throws IOException {
        // Writes are stopped, because of the synchronized method, so we're free
        var writer = newEngine.writer();
        for (Term term : rebuildQueue.deleteQueue()) {
            writer.deleteDocuments(term);
        }
        for (UpdateRec rec : rebuildQueue.updateQueue()) {
            writer.updateDocument(rec.term(), rec.doc());
        }
        for (Document doc : rebuildQueue.addQueue()) {
            writer.addDocument(doc);
        }
        this.rebuildQueue = null;

        var oldEngine = this.engine;

        this.engine = newEngine;
        writeCurrentDirectoryName(timestamp);


        if (oldEngine != null) {
            oldEngine.close();
        }
    }

    public void commit() throws IOException {
        getWriter().commit();
    }

    private IndexWriter getWriter() {
        return engine.writer();
    }

    public SearcherManager getSearcherManager() {
        return engine.searcherManager();
    }

    private ControlledRealTimeReopenThread<IndexSearcher> getNrtThread() {
        return engine.nrtThread();
    }
}
