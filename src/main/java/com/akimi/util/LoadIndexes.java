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
        emailIndex.startReload(triple -> {
            IndexWriter emailWriter = triple.writer();

            try (CsvReader<NamedCsvRecord> csv = CsvReader.builder().ofNamedCsvRecord(Path.of("emails.csv"))) {
                for (NamedCsvRecord rec : csv) {
                    String message = rec.getField("message");
                    if (message == null || message.isBlank()) continue;

                    String headers = isolateHeaders(message);
                    String id = parseMessageId(headers);

                    emailWriter.addDocument(EmailIndex.document(id, message));
                }
                emailWriter.commit();
            } catch (IOException e) {
                throw new RuntimeException("Email indexing failed", e);
            }
        });
    }

    public static void reloadEmailAddressIndex(EmailAddressIndex addressIndex) {
        addressIndex.startReload(triple -> {
            IndexWriter addressWriter = triple.writer();
            var seenAddresses = new HashSet<String>();

            try (CsvReader<NamedCsvRecord> csv = CsvReader.builder().ofNamedCsvRecord(Path.of("emails.csv"))) {
                for (NamedCsvRecord rec : csv) {
                    String message = rec.getField("message");
                    if (message == null || message.isBlank()) continue;

                    String headers = isolateHeaders(message);
                    String address = parseAddress(headers);

                    if (seenAddresses.add(address)) {
                        addressWriter.addDocument(EmailAddressIndex.document(address));
                    }
                }
                addressWriter.commit();
            } catch (IOException e) {
                throw new RuntimeException("Address indexing failed", e);
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
