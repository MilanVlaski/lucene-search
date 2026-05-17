package com.akimi;

import org.apache.lucene.store.FSDirectory;

import java.io.IOException;
import java.nio.file.Paths;

//TIP To <b>Run</b> code, press <shortcut actionId="Run"/> or
// click the <icon src="AllIcons.Actions.Execute"/> icon in the gutter.
public class Main {
    public static void main(String[] args) {

        try {
        var dir = FSDirectory.open(Paths.get("emailindex"));

            var emailIndex = new EmailIndex(dir,
                new BaseLuceneIndex(dir)
            );

            emailIndex.addToIndex();
            emailIndex.rebuildIndexFrom("bla");

            emailIndex.search();

            emailIndex.deleteFromIndex();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

    }
}
