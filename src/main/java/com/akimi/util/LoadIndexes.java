package com.akimi.util;

import com.akimi.*;
import de.siegmar.fastcsv.reader.CsvReader;
import de.siegmar.fastcsv.reader.NamedCsvRecord;
import org.apache.lucene.index.IndexWriter;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Loads the Enron dataset using the encapsulated startReload architecture.
 */
public class LoadIndexes {

    private static final Pattern ID_PAT = Pattern.compile("Message-ID:\\s*<([^>]+)>", Pattern.CASE_INSENSITIVE);
    private static final Pattern FROM_PAT = Pattern.compile("From:\\s*([^\\r\\n]+)", Pattern.CASE_INSENSITIVE);

    public static void main(String[] args) {
        var addressIndex = new EmailAddressIndex(new BaseLuceneIndex(Path.of("indexes/email-addresses")));
        var emailIndex = new EmailIndex(new BaseLuceneIndex(Path.of("indexes/emails")));

        System.out.println("Starting bulk address index reload...");
        reloadEmailAddressIndex(addressIndex);

        System.out.println("Starting bulk email index reload...");
        reloadEmailIndex(emailIndex);

        System.out.println("Bulk reload complete. Live index readers swapped successfully.");
    }

    private static void reloadEmailIndex(EmailIndex emailIndex) {
        var indexPath = emailIndex.getPath();
        emailIndex.startReload(engine -> {
            IndexWriter emailWriter = engine.writer();

            try (var directory =
                     org.apache.lucene.store.FSDirectory.open(indexPath);
                 var reader = org.apache.lucene.index.DirectoryReader.open(directory)) {

                int maxDoc = reader.maxDoc();
                var liveDocs = org.apache.lucene.index.MultiBits.getLiveDocs(reader);

                for (int i = 0; i < maxDoc; i++) {
                    if (liveDocs != null && !liveDocs.get(i)) continue;

                    var oldDoc = reader.storedFields().document(i);
                    emailWriter.addDocument(oldDoc);
                }
                emailWriter.commit();
            } catch (IOException e) {
                throw new RuntimeException("Email index migration failed", e);
            }
        });
    }

    public static void reloadEmailAddressIndex(EmailAddressIndex addressIndex) {
        var oldIndexPath = addressIndex.getPath();
        addressIndex.startReload(engine -> {
            IndexWriter addressWriter = engine.writer();
            var seenAddresses = new HashSet<String>();

            try (var directory =
                     org.apache.lucene.store.FSDirectory.open(oldIndexPath);
                 var reader = org.apache.lucene.index.DirectoryReader.open(directory)) {

                int maxDoc = reader.maxDoc();
                var liveDocs = org.apache.lucene.index.MultiBits.getLiveDocs(reader);

                for (int i = 0; i < maxDoc; i++) {
                    if (liveDocs != null && !liveDocs.get(i)) continue;

                    var oldDoc = reader.storedFields().document(i);
                    String address = oldDoc.get("address");

                    if (address != null && seenAddresses.add(address)) {
                        addressWriter.addDocument(EmailAddressIndex.document(address));
                    }
                }
                addressWriter.commit();
            } catch (IOException e) {
                throw new RuntimeException("Address index migration failed", e);
            }
        });
    }

    private static String isolateHeaders(String message) {
        int headerEnd = message.indexOf("\n\n");
        if (headerEnd == -1) headerEnd = message.indexOf("\r\n\r\n");
        return (headerEnd != -1) ? message.substring(0, headerEnd) : message;
    }

    private static String parseMessageId(String headers) {
        Matcher m = ID_PAT.matcher(headers);
        return m.find() ? m.group(1).trim() : "UNKNOWN_" + System.nanoTime();
    }

    private static String parseAddress(String headers) {
        Matcher m = FROM_PAT.matcher(headers);
        return m.find() ? m.group(1).trim() : "unknown@example.com";
    }
}
