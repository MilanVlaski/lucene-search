- [ ] Code review with the AI at https://gemini.google.com/app/4ef06421a70acc5d

## Reason for synchronized rebuild queue assignment

| Time | Thread A (addDocument)               | Thread B (startReload)                 | The State / The Problem                                                                                                    |
|------|--------------------------------------|----------------------------------------|----------------------------------------------------------------------------------------------------------------------------|
| T1   | engine.writer().addDocument("Doc X") |                                        | "Doc X" is written to the Old Index.                                                                                       |
| T2   |                                      | Database Snapshot Happens              | The rebuilder reads the database to populate the New Index. Because Doc X was just added, the database snapshot misses it. |
| T3   |                                      | this.rebuildQueue = new RebuildQueue() | The queue is finally created, but it's too late for Doc X.                                                                 |
| T4   | if (rebuildQueue != null)            |                                        | Thread A finally checks the queue. It reads null because T1 happened before the queue was initialized.                     |
| T5   | Skips queueing.                      |                                        | Doc X is NOT added to the catch-up queue.                                                                                  |
| T6   |                                      | switchToNewIndex()                     | The system swaps the Old Index out for the New Index.                                                                      |
