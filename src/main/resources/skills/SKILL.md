---
name: git-cherry-pick-multi
description: >
  Automatisiert das Cherry-Picking von einem oder mehreren Commits auf mehrere Zielbranches.
  Verwende diesen Skill, wenn der Nutzer Commits auf mehrere Branches übertragen, Bugfixes
  backporten, einen Patch auf mehrere Release-Branches verteilen oder Massen-Cherry-Picks
  durchführen möchte. Triggert bei Formulierungen wie "cherry-pick auf mehrere Branches",
  "Commit backporten", "Fix auf alle Release-Branches anwenden", "cherry-pick über Branches
  hinweg" oder jeder Anfrage, die Cherry-Pick zusammen mit einer Liste von Branches erwähnt.
---

# Git Cherry-Pick Multi-Branch

Automatisiert `git cherry-pick` von einem oder mehreren Commits auf eine Liste von
Zielbranches — mit Konfliktbehandlung und Zusammenfassungsbericht.

## Wann verwenden

- Der Nutzer möchte Commit(s) auf **mehrere** Zielbranches cherry-picken
- Backporting von Fixes auf Release-Branches (z.B. `release/1.0`, `release/2.0`, …)
- Verteilung eines Hotfixes auf Wartungs-Branches

## Benötigte Eingaben vom Nutzer

1. **Commit(s)**: Ein oder mehrere Commit-SHAs (oder Referenzen wie `HEAD`, `feature~2`, Tag-Namen)
2. **Zielbranches**: Eine Liste von Branch-Namen, auf die cherry-gepickt werden soll
3. **Repository-Pfad** (optional): Standard ist das aktuelle Arbeitsverzeichnis

## Ablauf

### Schritt 0 — Umgebung prüfen

```bash
# Bestätigen, dass wir in einem Git-Repo sind
git rev-parse --is-inside-work-tree

# Prüfen, ob der Arbeitsbaum sauber ist
git status --porcelain
```

Falls der Arbeitsbaum unsauber ist: den Nutzer warnen und vorschlagen, Änderungen zu stashen oder zu committen.

### Schritt 1 — Eingaben validieren

- Jeden Commit-SHA prüfen: `git cat-file -t <sha>`
- Jeden Zielbranch prüfen: `git rev-parse --verify <branch>`
- Den aktuellen Branch merken, um später zurückzukehren:
  ```bash
  ORIGINAL_BRANCH=$(git symbolic-ref --short HEAD 2>/dev/null || git rev-parse HEAD)
  ```

### Schritt 2 — Cherry-Pick-Schleife

Für jeden **Zielbranch**, für jeden **Commit** (in Reihenfolge):

```bash
git checkout <zielbranch>
git cherry-pick <commit-sha> --no-commit  # Erst stagen, um zu inspizieren
```

#### Mögliche Ergebnisse pro Cherry-Pick:

| Ergebnis | Erkennung | Aktion |
|---|---|---|
| **Sauber übernommen** | Exit-Code 0, keine Konfliktmarker | `git commit -C <original-sha>` → ERFOLG loggen |
| **Bereits vorhanden** | `git cherry-pick` meldet "nothing to commit" oder leerer Diff | ÜBERSPRUNGEN loggen, `git cherry-pick --skip` |
| **Trivialer Konflikt** | Konfliktmarker vorhanden, aber nur Whitespace oder offensichtliche Einzeiler-Auflösung | Automatisch auflösen, dann `git add . && git commit` → AUTOMATISCH GELÖST loggen |
| **Komplexer Konflikt** | Mehrzeiliger Konflikt, semantische Mehrdeutigkeit | **Pausieren und Nutzer fragen** (Diff anzeigen). Optionen: interaktiv lösen, überspringen, oder restliche für diesen Branch abbrechen |
| **Fehlende Abhängigkeit** | Übernahme gelingt, aber Imports/Aufrufe referenzieren unbekannte Symbole | Nutzer nach Commit warnen → WARNUNG loggen |

#### Konfliktlösungsstrategie (gestuft)

**Stufe 1 — Automatisch lösen (trivial):**
- Nur Whitespace-Unterschiede
- Eine Seite ist leer (klare Löschung vs. Ergänzung)
- Identischer Inhalt auf beiden Seiten (Scheinkonflikt)

**Stufe 2 — Nutzer fragen (komplex):**
- Betroffene Hunks mit Kontext anzeigen
- Optionen anbieten:
    - "OURS verwenden (Version vom Zielbranch)"
    - "THEIRS verwenden (Cherry-Pick-Version)"
    - "Manuell bearbeiten" → Datei anzeigen, Änderungen akzeptieren
    - "Diesen Commit auf diesem Branch überspringen"
    - "Restliche Cherry-Picks für diesen Branch abbrechen"

**Stufe 3 — Überspringen:**
- Falls der Nutzer Überspringen wählt: `git cherry-pick --abort` und weiter

### Schritt 3 — Zum ursprünglichen Branch zurückkehren

```bash
git checkout $ORIGINAL_BRANCH
```

### Schritt 4 — Bericht erstellen

Bericht in **beiden** Formaten erstellen:

#### Terminal-Ausgabe

Farbige Zusammenfassungstabelle ausgeben:

```
╔══════════════════════════════════════════════════════════════╗
║           Cherry-Pick-Bericht — 2025-04-13 14:30            ║
╠══════════════════════════════════════════════════════════════╣
║ Commit          │ Branch        │ Status                    ║
╠═════════════════╪═══════════════╪═══════════════════════════╣
║ abc1234         │ release/1.0   │ ✅ ERFOLG                 ║
║ abc1234         │ release/2.0   │ ⚠️  AUTOM. GELÖST         ║
║ abc1234         │ release/3.0   │ ❌ KONFLIKT (übersprungen)║
║ def5678         │ release/1.0   │ ⏭️  ÜBERSPRUNGEN (vorh.)  ║
║ def5678         │ release/2.0   │ ✅ ERFOLG                 ║
╚══════════════════════════════════════════════════════════════╝

Zusammenfassung: 2 erfolgreich, 1 autom. gelöst, 1 übersprungen, 1 Konflikt
```

#### Markdown-Berichtsdatei

Speichern als `cherry-pick-bericht-<zeitstempel>.md` mit:

- Datum/Uhrzeit
- Cherry-gepickte Commits (SHA + Original-Nachricht)
- Ergebnistabelle pro Branch
- Konfliktdetails (welche Dateien, welche Art)
- Warnungen (fehlende Abhängigkeiten erkannt)
- Zusammenfassungsstatistik

## Script-Referenz

Das Hilfsskript `scripts/cherry-pick-multi.sh` kann für die Cherry-Pick-Schleife
verwendet werden. Einlesen mit:

```
view /pfad/zum/skill/scripts/cherry-pick-multi.sh
```

Das Script für die mechanischen Git-Operationen verwenden, aber die Konfliktlösung
interaktiv im Gespräch mit dem Nutzer durchführen.

## Sonderfälle

- **Detached HEAD**: Wenn der aktuelle Zustand ein detached HEAD ist, den SHA statt des Branch-Namens speichern
- **Merge-Commits**: Den Nutzer warnen, dass Cherry-Pick von Merge-Commits das Flag `-m <parent>` benötigt. Fragen, welcher Parent verwendet werden soll.
- **Rebase aktiv**: Erkennen und abbrechen — keinen Cherry-Pick während eines aktiven Rebase starten
- **Nur-Remote-Branches**: Falls ein Branch nur auf dem Remote existiert, anbieten, zuerst einen lokalen Tracking-Branch zu erstellen
- **Berechtigungsfehler**: Falls nach dem Cherry-Pick ein Push nötig ist, dies im Bericht vermerken, aber nicht automatisch pushen, es sei denn, der Nutzer fragt explizit danach

## Wichtige Hinweise

- **Niemals force-pushen**, es sei denn, der Nutzer fragt ausdrücklich danach
- **Niemals Branches löschen**
- Immer zum ursprünglichen Branch zurückkehren
- Wenn etwas katastrophal schiefgeht: `git cherry-pick --abort` und Bericht erstatten