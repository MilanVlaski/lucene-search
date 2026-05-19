package com.akimi.util;

import com.akimi.*;
import de.siegmar.fastcsv.reader.CsvReader;
import de.siegmar.fastcsv.reader.NamedCsvRecord;
import org.apache.lucene.index.IndexWriter;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * This class loads the enron dataset, and can serve as an example of how to
 * reload an index.
 */
public class LoadIndexes {

    private static final Pattern ID_PAT = Pattern.compile("Message-ID:\\s*<([^>]+)>", Pattern.CASE_INSENSITIVE);
    private static final Pattern FROM_PAT = Pattern.compile("From:\\s*([^\\r\\n]+)", Pattern.CASE_INSENSITIVE);

    public static void main(String[] args) {
        var addressIndex = new EmailAddressIndex(new BaseLuceneIndex(Path.of("indexes/email-addresses")));
        var emailIndex = new EmailIndex(new BaseLuceneIndex(Path.of("indexes/emails")));

        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd.HH.mm.ss"));

        // Local state tracking for in-memory deduplication (Flat & Safe)
        var seenAddresses = new HashSet<String>();

        // FIX 1: Wrap writers in try-with-resources to guarantee safe close/lock release on failure
        try (
            IndexWriter addressWriter = addressIndex.createTemporaryRebuildWriter(timestamp);
            IndexWriter emailWriter = emailIndex.createTemporaryRebuildWriter(timestamp);
            CsvReader<NamedCsvRecord> csv = CsvReader.builder().ofNamedCsvRecord(Path.of("emails.csv"))
        ) {

            for (NamedCsvRecord rec : csv) {
                var message = rec.getField("message");
                if (message == null || message.isBlank()) continue;

                // FIX 2: Isolate headers before running regex to save CPU
                int headerEnd = message.indexOf("\n\n");
                if (headerEnd == -1) headerEnd = message.indexOf("\r\n\r\n");
                String headers = (headerEnd != -1) ? message.substring(0, headerEnd) : message;

                var id = parseMessageId(headers);
                emailWriter.addDocument(EmailIndex.document(id, message));

                var address = parseAddress(headers);

                // FIX 3: Use fast HashSet check + addDocument instead of costly updateDocument
                if (seenAddresses.add(address)) {
                    addressWriter.addDocument(EmailAddressIndex.document(address));
                }
            }

            // FIX 4: Explicitly commit data to disk segments
            addressWriter.commit();
            emailWriter.commit();



        } catch (IOException e) {
            throw new RuntimeException("Failed to execute index bulk reload", e);
        }
        // FIX 5: Execute the atomic cutover for live readers
        try {
            addressIndex.switchToNewIndex(timestamp);
            emailIndex.switchToNewIndex(timestamp);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        System.out.println("Rebuild complete. Swapped to: " + timestamp);
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
