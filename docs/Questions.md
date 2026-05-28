- Should filling the index happen outside of the class?
- Should the base index class handle the index file and the .ini version?
- Which approach do we take for "hotswaps"?

---
Imam BaseLuceneIndex, EmailAddressIndex i EmailIndex. Na kraju mi BaseLuceneIndex klasa ima referencu na IndexWriter, SearcherManager, ControlledRealTimeReopenThread<IndexSearcher>.
Kad se radi swap indeksa, ta nam klasa kreira neki novi IndexWriter, njime napunimo novi indeks, i onda mijenjamo reference na one 3 instance. Prvo promijenimo reference, a onda pozovemo `close()` metodu da se to pocisti, i da bi preostali threadovi u aplikaciji iskoristili taj indeks.
---
Isto tako BaseLuceneIndex hendluje `index.ini` fajl, kreiranje itd. 

Takodje, konkretne klase "posudjuju" od bazne ove referense, da bi uradile `searcherManager.acquire()` ili za ovaj thread.

Za sad imam main klasu koja radi pretragu. Nemam `commit()` nigdje pozvan.

Konkretne klase referenciraju Analyzer, jer mi je on nekako specifičan. Pa se s tim analyzerom pozivaju neke metode iz bazne klase vezane za indeks.
