# Task / Requirements

Erweitere Modul `02-ai-agent-filter` um die Fähigkeit, mehrere Werte für denselben Suchparameter zu unterstützen. Ein Benutzer soll nach "Kunden aus Berlin und Köln" suchen können, was intern zu einer OR-Verknüpfung für das `city`-Feld führt: `(city LIKE '%Berlin%' OR city LIKE '%Köln%')`.

**Funktionale Anforderungen:**
- Natural-Language-Queries wie "Zeige mir Kunden aus Berlin und Köln" oder "Customers from Germany or Austria" sollen unterstützt werden
- Mehrere Werte für folgende Feldtypen:
  - **String-Felder:** `city`, `country`, `companyName`, `contactName`, `email`, `phone`, `postalCode`, `street`, `houseNumber`
  - **CreditRating:** Mehrere Kreditwürdigkeits-Werte (`GOOD`, `MEDIUM`, `POOR`)
  - **Datumsfelder:** `customerSince`, `lastOrderDate` — mit automatischer Jahresbereich-Interpretation
  - **`annualRevenue`:** Mehrere Umsatz-Bereiche (`RevenueRange` mit `atLeast`/`atMost`, siehe unten) — neuer Filterparameter, existiert bisher nicht in Modul 02
- Mehrwerte innerhalb eines Feldes werden mit OR verknüpft
- Verschiedene Felder werden weiterhin mit AND verknüpft (bestehende flache Struktur bleibt erhalten)
- Die UI und das Benutzerverhalten bleiben unverändert

**Beispiele:**
- String Multi-Value: "Kunden aus Berlin oder Hamburg" → `(city LIKE '%Berlin%' OR city LIKE '%Hamburg%')`
- CreditRating Multi-Value: "Kunden mit GOOD oder MEDIUM Rating" → `(creditRating='GOOD' OR creditRating='MEDIUM')`
- Datumsfeld Multi-Value: "Kunden seit 2020 oder 2021" → `(customerSince >= '2020-01-01' AND customerSince <= '2020-12-31') OR (customerSince >= '2021-01-01' AND customerSince <= '2021-12-31')`
- `annualRevenue` Multi-Value: "Kunden mit Umsatz über 500.000 oder unter 50.000" → `(annualRevenue >= 500000) OR (annualRevenue <= 50000)`
- Kombiniert: "Kunden aus Berlin oder Hamburg mit Credit Rating GOOD oder MEDIUM" → `(city='Berlin' OR city='Hamburg') AND (creditRating='GOOD' OR creditRating='MEDIUM')`

**Datumsfeld-Semantik (Option A):**
Das LLM übergibt Datumsangaben im ISO-Format (z.B. `2021-01-01`). Java interpretiert jedes übergebene Datum automatisch als Jahresbereich — von 1. Januar bis 31. Dezember desselben Jahres. Bei Multi-Value werden diese Bereiche mit OR verknüpft.

**`annualRevenue`-Semantik (neu, Bereichsliste):**
`annualRevenue` (`BigDecimal`) ist ein stetiges Zahlenfeld, kein diskreter Wert wie `city` oder `creditRating`, und existiert bisher **gar nicht** als Filterparameter in Modul 02 (im Gegensatz zu den anderen Feldern, die bereits als Einzelwert funktionieren). Es wird als neue Liste von Bereichen `List<RevenueRange>` modelliert (`RevenueRange(BigDecimal atLeast, BigDecimal atMost)`, beide nullable für offene Bereiche), analog zum Datums-Muster: ein einzelner Bereich entspricht dem heutigen Einzelwert-Verhalten, mehrere Bereiche werden mit OR verknüpft.
- "Umsatz über 500.000" → `[{atLeast: 500000, atMost: null}]`
- "Umsatz zwischen 50.000 und 200.000" → `[{atLeast: 50000, atMost: 200000}]`
- "Umsatz über 500.000 oder unter 50.000" → `[{atLeast: 500000, atMost: null}, {atLeast: null, atMost: 50000}]`

**Ausgeschlossen aus dem Scope:**
- `active` (boolean) wird nicht unterstützt (aktuell kein Filter-Parameter)

**Wichtig:** Dies ist ein bewusster Kontrast zu Modul `03-ai-structured-filter`, das bereits eine vollständige AND/OR/NOT-Baumstruktur unterstützt. Modul 02 behält seine grundlegend flache Struktur bei (keine Cross-Field-OR, kein NOT), erweitert aber die Fähigkeit, innerhalb eines Feldes mehrere Werte per OR zu kombinieren.

# Context
- **Affected modules:** `02-ai-agent-filter` (nur dieses Modul)
- **Purpose:** Demo für Konferenz-Talks über Natural Language Filtering. Der Code soll präsentabel, leicht verständlich und erweiterbar sein. Das Feature zeigt die schrittweise Evolution von einfachem Tool Calling (aktuell) zu komplexeren Filter-Capabilities, ohne die vollständige Baumstruktur von Modul 03 vorwegzunehmen.
- **Relevant existing parts:**
  - `CustomerSearchCriteria.java` — Record mit aktuell Einzelwerten (String, LocalDate, CreditRating); `annualRevenue` fehlt hier komplett und muss als neues Feld (`List<RevenueRange>`) hinzugefügt werden, nicht nur erweitert
  - `CustomerSearchToolCallingService.java` — `@Tool`-Methode `searchCustomers` mit Einzelwert-Parametern
  - `CustomerSpecifications.java` — Baut JPA Specifications aus Einzelwerten (ein Predicate pro Feld); enthält noch kein Revenue-Predicate
  - `CustomerSearchToolCallingServiceTest.java` — Unit Test für die Tool-Extraction
  - `CustomerSearchAgentIT.java` — Integration Tests gegen Ollama
  - `Customer.java` — Entity mit `annualRevenue` als `BigDecimal` (Demodaten: 1.300–249.900)

# Approach
1. **Plan Mode:** Erstelle zunächst einen detaillierten Implementierungsplan und präsentiere ihn. Prüfe auf:
   - Welche Parameter-Typen müssen geändert werden?
     - String → `List<String>` oder `Collection<String>`
     - `CreditRating` → `List<CreditRating>`
     - `LocalDate` → `List<LocalDate>` (mit Jahresbereich-Logik)
     - `annualRevenue` (neu) → `List<RevenueRange>` (neuer Record mit nullable `atLeast`/`atMost`)
   - Wie sollte die JPA Specification-Generierung aussehen?
     - OR-Verknüpfung für String-Listen (LIKE)
     - OR-Verknüpfung für CreditRating-Listen (BETWEEN für Scores)
     - OR-Verknüpfung für Datumsfeld-Listen (Jahresbereiche: `BETWEEN year-01-01 AND year-12-31`)
     - OR-Verknüpfung für `RevenueRange`-Listen (je Bereich `>=atLeast AND <=atMost`, offene Bereiche wenn `atLeast`/`atMost` null)
   - Wie muss die Tool-Beschreibung angepasst werden, damit das LLM weiß, dass es Arrays/Listen übergeben kann?
   - Wie wird die Jahresbereich-Logik implementiert (Detection: ist das Datum ein 01.01.? Oder immer als Jahresbereich behandeln?)
   - Welche Tests müssen ergänzt werden?
   - Gibt es Konflikte mit der bestehenden Architektur oder Inkonsistenzen?

   Warte auf meine Freigabe, bevor du mit der Implementierung beginnst.

2. **Implementierung:** Arbeite in logischen, verifizierbaren Schritten. Nach jedem abgeschlossenen Schritt:
   - Führe `./mvnw verify -pl 02-ai-agent-filter` aus (alle Tests müssen grün sein)
   - Erstelle einen Commit mit Conventional Commits Format (kein Push)

   Mögliche Schritte (Reihenfolge anpassbar):
   - Erweitere `CustomerSearchCriteria` für Multi-Value-Parameter
   - Passe `CustomerSpecifications` an, um OR-Verknüpfungen zu generieren
   - Aktualisiere die `@Tool`-Methode `searchCustomers` und deren Beschreibung
   - Erweitere `CustomerSearchToolCallingServiceTest` um Multi-Value-Test-Cases (Unit Tests)
   - Erweitere `CustomerListViewBrowserlessTest` um Multi-Value-Test-Cases (UI Browserless Tests mit Fake Agent)
   - Füge Test-Cases zu `CustomerSearchAgentIT` hinzu (Integration Tests gegen Ollama)
   - Füge Test-Cases zu `CustomerListViewBrowserlessIT` hinzu (UI Browserless Integration Tests gegen Ollama)

   `04-ollama-benchmark/BenchmarkLocalModels.java` bleibt unverändert: es benchmarkt laut
   `CLAUDE.md` und eigenem Docstring ausschließlich Modul 03 (`03-ai-structured-filter`), nicht
   Modul 02 — Testfälle dort gehören nicht in den Scope dieser Aufgabe.

3. **Selbstständige Verifikation:** Iteriere eigenständig auf Fehlern. Zeige mir keine Fehlermeldungen zur Analyse, wenn du sie selbst reproduzieren und beheben kannst. Führe alle Tests mehrfach aus.

4. **Dokumentation:** Aktualisiere `02-ai-agent-filter/README.md` mit den neuen Capabilities. Entferne veraltete Informationen. Aktualisiere `CLAUDE.md` falls nötig (aber nur falls strukturelle Änderungen die Architektur-Beschreibung betreffen).

# Definition of Done (zusätzlich zu CLAUDE.md)

## Funktionale Kriterien:
- ✅ **String Multi-Value:** Query "Customers from Berlin and Cologne" liefert Kunden aus beiden Städten (OR-Verknüpfung)
- ✅ **String Multi-Value:** Query "Companies named Acme or Globex" liefert beide Firmen
- ✅ **CreditRating Multi-Value:** Query "Customers with GOOD or MEDIUM credit rating" liefert Kunden mit beiden Ratings
- ✅ **Datumsfeld Multi-Value:** Query "Customers since 2020 or 2021" liefert Kunden, die im Jahr 2020 ODER im Jahr 2021 Kunde geworden sind (Jahresbereiche)
- ✅ **`annualRevenue` Multi-Value:** Query "Customers with annual revenue over 500000 or under 50000" liefert Kunden aus beiden offenen Bereichen (OR-Verknüpfung)
- ✅ **`annualRevenue` Range:** Query "Customers with annual revenue between 100000 and 200000" liefert Kunden im geschlossenen Bereich
- ✅ **Kombiniert:** Query "Customers from Germany or Austria with credit rating GOOD or MEDIUM" liefert korrekte Kombination aus (country OR) AND (creditRating OR)
- ✅ **Rückwärtskompatibilität:** Einzelwert-Queries ("Customers from Berlin") funktionieren weiterhin wie bisher
- ✅ **Rückwärtskompatibilität:** Leere Query zeigt alle Kunden (keine Regression)

## Technische Kriterien:
- ✅ `./mvnw verify -pl 02-ai-agent-filter` läuft ohne Fehler durch (alle Unit Tests grün)
- ✅ `./mvnw verify -pl 02-ai-agent-filter -Pit-local-ollama` läuft durch (alle Integration Tests grün, falls Ollama verfügbar)
- ✅ Neue Test-Cases in `CustomerSearchToolCallingServiceTest`:
  - Multi-Value String-Felder (z.B. mehrere Städte)
  - Multi-Value CreditRating
  - Multi-Value Datumsfelder mit Jahresbereich-Logik
  - Multi-Value `annualRevenue` (geschlossener Bereich, offener Bereich atLeast-only/atMost-only, mehrere OR-verknüpfte Bereiche, leere Liste)
  - Kombination mehrerer Multi-Value-Felder
- ✅ Mindestens 3 neue Test-Cases in `CustomerSearchAgentIT`:
  - Natural Language Query mit String Multi-Value
  - Natural Language Query mit CreditRating Multi-Value
  - Natural Language Query mit Datumsfeld Multi-Value
  - zusätzlich mindestens 1 Natural Language Query mit `annualRevenue` Multi-Value/Range
- ✅ Keine Code-Duplikation, klare Trennungen zwischen Layers
- ✅ Keine Compiler-Warnings
- ✅ System.out.println oder Debug-Code entfernt

## UI-Verifikation:
- ✅ App startet mit `./mvnw spring-boot:run -pl 02-ai-agent-filter` ohne Fehler
- ✅ UI-Verhalten ist unverändert (TextField funktioniert wie bisher)
- ✅ Browserless Tests (`CustomerListViewBrowserlessTest`) laufen durch und verifizieren das UI-Verhalten
- ✅ Browserless Integration Tests (`CustomerListViewBrowserlessIT`) mit echtem Ollama laufen durch (falls verfügbar)

## Dokumentation:
- ✅ `02-ai-agent-filter/README.md` beschreibt die Multi-Value-Capability
- ✅ Beispiel-Queries in der Dokumentation aktualisiert
- ✅ Keine veralteten oder widersprüchlichen Aussagen in READMEs

# Final Report
Am Ende erstelle eine Zusammenfassung mit:
- **Commits:** Was wurde pro Commit geändert (chronologisch)
- **Tests:** Welche Tests laufen jetzt (mit Anzahl), welche neuen Cases wurden hinzugefügt
- **Offene Punkte / Designentscheidungen:**
  - Wie wurde die Jahresbereich-Erkennung implementiert? (Immer als Jahresbereich, oder nur bei 01.01.?)
  - Wurden alle geplanten Feldtypen unterstützt? (String, CreditRating, Datumsfelder, `annualRevenue`)
  - Was wurde bewusst ausgeschlossen? (`active`)
  - Gibt es bekannte Limitierungen oder Edge Cases?