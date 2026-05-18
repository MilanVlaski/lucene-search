package com.akimi;

import java.io.IOException;
import java.nio.file.Path;

public class Main {
    public static void main(String[] args) {
        String search = "joh";
        System.out.println("searching for: " + search);

        var addressIndex = new EmailAddressIndex(
            new BaseLuceneIndex(Path.of("indexes/email-addresses"))
        );

        try {
            addressIndex.start();
            addressIndex.autocompleteAddress(search, 5)
                .forEach(System.out::println);

        } catch (IOException e) {
            throw new RuntimeException("Search operation failed", e);
        }
    }
}
