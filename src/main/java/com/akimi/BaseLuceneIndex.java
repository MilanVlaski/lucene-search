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

/**
 * Explanation why volatile is sufficient:
 * Volatile is sufficient because you are dealing with a single-writer,
 * multi-reader problem.
 * <ul>
 * <li>
 * Synchronized protects actions (mutation): It forces threads to take turns
 * .volatile is sufficient because you are dealing with a single-writer,
 * multi-reader problem.
 * </li>
 * <li>
 * Volatile protects visibility (reading): It does not lock
 * anything. Instead, it tells the JVM that this variable can change
 * at any moment. Whenever the background thread updates the reference,
 * all reading threads instantly see the new reference instead of a
 * cached, old one.
 * </li>
 * </ul>
 */
public class BaseLuceneIndex {
    public static final DateTimeFormatter DIRECTORY_FORMAT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd.HH.mm.ss");

    private final Path rootDir;
    private final Path iniPath;


    private volatile LuceneEngine engine;

    private List<Document> writeQueue;
    // You can delete either by term or query. Abstraction is leaking.
    private List<Term> deleteQueue;

    /**
     * Expects an existing index.ini file at the {@link #rootDir}.
     *
     * @param rootDir
     */
    public BaseLuceneIndex(Path rootDir) {
        this.rootDir = rootDir;
        this.iniPath = rootDir.resolve("index.ini");
    }

    public record LuceneEngine(
        IndexWriter writer,
        SearcherManager searcherManager,
        ControlledRealTimeReopenThread<IndexSearcher> nrtThread
    ) implements Closeable{
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

    public synchronized void init(IndexWriterConfig writerConfig) throws IOException {
        String currentDirName = readCurrentDirectoryName();
        if (currentDirName.isBlank()) {
            writeCurrentDirectoryName(LocalDateTime.now().format(BaseLuceneIndex.DIRECTORY_FORMAT));
        }
        currentDirName = readCurrentDirectoryName();

        assert currentDirName != null;
        Path indexPath = rootDir.resolve(currentDirName);

        Files.createDirectories(indexPath);


        this.engine = createEngineTriple(indexPath, writerConfig);
    }

    private LuceneEngine createEngineTriple(Path indexPath, IndexWriterConfig writerConfig) throws IOException {
        IndexWriter newWriter = new IndexWriter(FSDirectory.open(indexPath), writerConfig);
        SearcherManager newSearcherManager = new SearcherManager(newWriter, true, true, null);

        var newNrtThread = new ControlledRealTimeReopenThread<>(newWriter, newSearcherManager, 5.0, 0.025);
        newNrtThread.setName("Lucene-NRT-Thread-" + rootDir.getFileName() + "-"
            + indexPath.getFileName());
        newNrtThread.setDaemon(true);
        newNrtThread.start();

        return new LuceneEngine(newWriter, newSearcherManager, newNrtThread);
    }

    private synchronized void switchToNewIndex(String timestamp,
                                         LuceneEngine newTriple) throws IOException {
        Path indexPath = rootDir.resolve(timestamp);
        Files.createDirectories(indexPath);

        var oldTriple = this.engine;
        this.engine = newTriple;
        writeCurrentDirectoryName(timestamp);

        if(oldTriple != null) {
            // may be null if init was never called
            oldTriple.close();
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
        if (writeQueue != null) {
            writeQueue.add(doc);
        }

        long gen = getWriter().addDocument(doc);
        if (immediate) {
            getNrtThread().waitForGeneration(gen);
        }
    }

    public synchronized void updateDocument(Document doc,
                                            boolean immediate, Term term) throws IOException, InterruptedException {
        if (writeQueue != null) {
            writeQueue.add(doc);
        }

        long gen = getWriter().updateDocument(term, doc);
        if (immediate) {
            getNrtThread().waitForGeneration(gen);
        }
    }

    public synchronized void deleteDocument(Term term) throws IOException {
        if (deleteQueue != null) {
            deleteQueue.add(term);
        }
        getWriter().deleteDocuments(term);
    }

    // Consuming an IndexWriter is enough
    public void startReload(Consumer<LuceneEngine> rebuilder, IndexWriterConfig writerConfig) {
        try {
            String timestamp = LocalDateTime.now().format(BaseLuceneIndex.DIRECTORY_FORMAT);
            Path indexPath = rootDir.resolve(timestamp);

            synchronized (this) {
                writeQueue = new ArrayList<>();
                deleteQueue = new ArrayList<>();
            }

            var triple = createEngineTriple(indexPath, writerConfig);

            // Finishes building the new index
            rebuilder.accept(triple);

            // Make local copy of queue (adds are hitting add)
            var localWriteQueue = writeQueue;
            var localDeleteQueue = deleteQueue;

            synchronized (this) {
                switchToNewIndex(timestamp, triple);
                writeQueue = null;
                deleteQueue = null;
            }

            // If an addDocument happens during this loop, then elements come
            // out of roder
            for (Document doc : localWriteQueue) {
                addDocument(doc, false);
            }
            for (Term term : localDeleteQueue) {
                deleteDocument(term);
            }

        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            synchronized (this) {
                writeQueue = null;
                deleteQueue = null;
            }
        }

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
