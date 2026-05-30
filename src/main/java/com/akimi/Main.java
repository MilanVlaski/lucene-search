package com.akimi;

import com.akimi.util.LoadIndexes;
import org.w3c.dom.Text;

import javax.management.Query;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Scanner;

public class Main {
    public static void main(String[] args) {
        var addressIndex = new EmailAddressIndex(
            new BaseLuceneIndex(Path.of("indexes/email-addresses"))
        );

        var emailIndex = new EmailIndex(
            new BaseLuceneIndex(Path.of("indexes/emails"))
        );

        try {
            addressIndex.start();
            emailIndex.start();

            try (Scanner scanner = new Scanner(System.in)) {
                while (true) {
                    System.out.println("\n--- Main Menu ---");
                    System.out.println("1. Search for email address");
                    System.out.println("2. Add email address");
                    System.out.println("3. Delete email address");
                    System.out.println("4. Reload index - email addresses");
                    System.out.println("5. Commit - address index");
//                    System.out.println("4. Search for email");
                    System.out.println("0. Exit");
                    System.out.print("Enter choice: ");

                    if (!scanner.hasNextInt()) {
                        System.out.println("Invalid input. Enter a number.");
                        scanner.next();
                        continue;
                    }

                    var choice = scanner.nextInt();
                    if (choice == 0) {
                        System.out.println("bye");
                        break;
                    }

                    switch (choice) {
                        case 1 -> handleSearchAddress(scanner, addressIndex);
                        case 2 -> handleAddAddress(scanner, addressIndex);
                        case 3 -> handleDeleteAddress(scanner, addressIndex);
                        case 4 -> backgroundReload(addressIndex);
                        case 5 -> addressIndex.commit();
                        case 6 -> {

                        }
//                        case 4 -> handleSearchEmail(scanner, emailIndex);
                        default -> System.out.println("Unknown option.");
                    }
                }
            }
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("Index operation failed", e);
        }
    }

    // A more realistic use would be actually reading from a SQL db
    // and then checking but ehhh.
    private static void backgroundReload(EmailAddressIndex addressIndex) {
        {
            System.out.println("Reloading index in the background...");
            new Thread(() -> {
                try {
                    LoadIndexes.reloadEmailAddressIndex(addressIndex);
                    System.out.println("\n[Success] Email address index reload complete!");
                } catch (Exception e) {
                    System.err.println("\n[Error] Failed to reload email address index: " + e.getMessage());
                }
            }).start();
        }
    }

    private static void handleSearchAddress(Scanner scanner, EmailAddressIndex index) throws IOException {
        while (true) {
            System.out.print("\n[Search Address] Enter term to search (or 'q' to return): ");
            String input = scanner.next().trim();
            if ("q".equalsIgnoreCase(input)) break;

            System.out.println("Searching address for: " + input);
            index.autocompleteAddress(input, 5)
                    .forEach(System.out::println);
        }
    }

    private static void handleAddAddress(Scanner scanner, EmailAddressIndex index) throws IOException, InterruptedException {
        while (true) {
            System.out.print("\n[Add Address] Enter email to add (or 'q' to return): ");
            String input = scanner.next().trim();
            if ("q".equalsIgnoreCase(input)) break;

            System.out.println("Adding address: " + input);
            index.add(input, true);
        }
    }

    private static void handleDeleteAddress(Scanner scanner, EmailAddressIndex index) throws IOException {
        while (true) {
            System.out.print("\n[Delete Address] Enter email to delete (or 'q' to return): ");
            String input = scanner.next().trim();
            if ("q".equalsIgnoreCase(input)) break;

            System.out.println("Deleting address: " + input);
            index.delete(input);
        }
    }

    private static void handleSearchEmail(Scanner scanner, EmailIndex index) {
        while (true) {
            System.out.print("\n[Search Email] Enter query (or 'q' to return): ");
            String input = scanner.next().trim();
            if ("q".equalsIgnoreCase(input)) break;

            System.out.println("Searching emails for: " + input);
//             index.search(new Query(), )
        }
    }
}
