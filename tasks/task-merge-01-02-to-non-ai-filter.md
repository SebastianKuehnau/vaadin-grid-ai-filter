# Task
Module 01 und 02 zu einem Modul "01-non-ai-filter" zusammenlegen. Das neue Modul soll zwei Views enthalten: eine für In-Memory-Filterung mit Java-Mitteln und eine für Lazy-Loading mit Specification-basierter Datenbankfilterung. Beide Views sollen durch BrowserlessTests abgedeckt werden.

# Context
- Betroffene Module: `01-simple-filter`, `02-lazy-filter` (werden zu `01-non-ai-filter` zusammengelegt)
- Zweck: Klare Trennung zwischen nicht-AI-basierten Filteransätzen (In-Memory vs. Lazy Loading) und AI-basierten Ansätzen
- Relevante existierende Teile:
  - `01-simple-filter`: In-Memory-Filterung mit Textfeld
  - `02-lazy-filter`: Lazy Loading mit DataProvider und Textfeldern unter Spaltenüberschriften

# Approach
1. Erstelle zunächst einen Plan (Plan Mode) und zeige ihn mir. Warte auf mein OK.
2. Implementiere den Plan. Arbeite in logischen Schritten, committe nach jedem verifizierten Schritt
   (Conventional Commits, kein Push).
3. Verifiziere autonom gemäß Definition of Done in CLAUDE.md.
   Iteriere bei Fehlern selbstständig — präsentiere keine Fehlermeldungen zur Analyse,
   die du selbst reproduzieren und beheben kannst.

# Definition of Done (zusätzlich zu CLAUDE.md)
## Funktionale Anforderungen
- Modul `01-non-ai-filter` existiert mit korrekter Artefact ID und angepassten Klassennamen
- View 1 (Landing Page, Route-Alias "in-memory"):
  - Zeigt alle Customer-Daten aus der Datenbank an (in-memory geladen)
  - Filtern mit einem einzelnen Textfeld über Java-Mittel
  - Sortierung funktioniert
- View 2 (Route "lazy"):
  - Zeigt Customer-Daten mit Lazy Loading (DataProvider)
  - Textfelder unter jeder Spaltenüberschrift im Grid
  - Bei Änderung wird eine JPA Specification erstellt und Datenbankabfrage ausgeführt
  - Sortierung funktioniert
  - Mehrere Filter gleichzeitig möglich

## Test-Anforderungen
- BrowserlessTest für View 1 (In-Memory):
  - Alle Daten werden initial angezeigt
  - Sortierung funktioniert
  - Filter nach bestimmten Personen funktioniert
  - Filter nach Datum von gestern funktioniert
  - Filter nach Stadt "Berlin" funktioniert

- BrowserlessTest für View 2 (Lazy Loading):
  - Alle Daten werden initial angezeigt
  - Sortierung funktioniert
  - Einzelne Filter funktionieren (Person, Datum, Stadt)
  - Kombinierte Filter funktionieren:
    - Nach Name und Stadt gleichzeitig
    - Nach Datum von gestern und positiver Kreditwürdigkeit gleichzeitig

## Verifikation
- `./mvnw verify -pl 01-non-ai-filter` ist grün
- Beide alte Module (`01-simple-filter`, `02-lazy-filter`) sind entfernt
- README.md und CLAUDE.md sind aktualisiert und spiegeln die neue Modulstruktur wider
- Alle veralteten Referenzen zu den alten Modulen sind entfernt

# Final Report
Fasse am Ende zusammen: was wurde geändert (pro Commit), welche Tests sind grün,
offene Punkte/Entscheidungen, die ich treffen muss.