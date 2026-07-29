# Vergleich von Branching-Modellen

**git-flow (klassisch) · git-flow-next · Trunk-Based Development**

> **Hinweis zur Einordnung:** *Klassisches git-flow* und *Trunk-Based Development* sind Branching-*Modelle* (Konzepte). *git-flow-next* (git-flow.sh) ist dagegen ein *CLI-Werkzeug* von Tower, das mehrere Workflows beherrscht. In den Modell-Zeilen erscheint es daher mit seinem **Gitflow-Preset** (= inhaltlich wie klassisches git-flow); wo es sich absetzt, zeigen das die **Tooling-Zeilen**.

---

## 1. Struktur & Topologie

| Kriterium | klassisches git-flow | git-flow-next (Gitflow-Preset) | Trunk-Based Development |
|---|---|---|---|
| Dauerhafte Branches | `main` + `develop` | `main` + `develop` (Preset) | nur `trunk`/`main` |
| Anzahl permanenter Branches | 2 | 2 (konfigurierbar) | 1 |
| Temporäre Branch-Typen | `feature/`, `release/`, `hotfix/` (Original); `support/` nur via gitflow-avh, experimentell | `feature/`, `release/`, `hotfix/`, `support/` (Defaults); `bugfix/` u. a. frei ergänzbar | kurzlebige Feature-Branches; optional Release-Branches |
| Integrations-Zielbranch | `develop` (Features) | `develop` (Preset) | `trunk` direkt |
| Namenskonventionen/Präfixe | fest vorgegeben | Default wie Gitflow, frei konfigurierbar | keine vorgeschrieben |

### 1a. Branch-Beziehungen (Unterabschnitt)

*parent/child & Fließrichtung — upstream = beim Finish nach oben mergen; downstream = Parent-Änderungen nach unten propagieren*

| Faktor | klassisches git-flow | git-flow-next (Gitflow-Preset) | Trunk-Based Development |
|---|---|---|---|
| Base-Hierarchie (parent → child) | `main` → `develop` (konzeptionell, unbenannt) | `main` → `develop`, *explizit konfiguriert* (`gitflow.branch.*.parent`) | keine — nur `trunk` |
| Topic-Parent (Abzweig & Upstream-Ziel) | `feature`←`develop`; `release`←`develop`; `hotfix`←`main`; `support`←Tag auf `main` | dieselben Parents, deklariert; Upstream-Merge-Strategie pro Typ einstellbar | kurzlebige FB ← `trunk`; Release-Branch ← `trunk` |
| Downstream-Propagation (parent → child) | manuell: `release`/`hotfix` zurück nach `develop` mergen | automatisch (Dependency Tracking), Strategie konfigurierbar | entfällt; Fixes fix-forward, ggf. Cherry-pick auf Release-Branch |
| Terminologie im Modell nativ? | nein (implizit) | ja (Kernkonzept) | n. z. (flache Struktur) |

---

## 2. Workflow & Prozess

| Kriterium | klassisches git-flow | git-flow-next | Trunk-Based Development |
|---|---|---|---|
| Lebensdauer Feature-Branches | lang (Tage–Wochen) | lang (Preset) | sehr kurz (< 1–2 Tage) |
| Integrationshäufigkeit | niedrig (erst bei „finish“) | niedrig (Preset) | sehr hoch (≥ 1×/Tag pro Dev) |
| Merge Feature→Integration | `merge --no-ff` nach `develop` | konfigurierbare Strategie (Default: merge) | direkt-Commit oder kurzer PR (squash/merge) |
| Reintegration Release/Hotfix | Merge nach `main` **und** zurück `develop` | dito, konfigurierbar | i. d. R. entfällt (fix-forward) |
| Direkt-zu-Trunk erlaubt? | nein | nein (Preset) | ja (v. a. kleine Teams) |
| PR-/Review-Gate | nicht eingebaut | nicht erzwungen (Tool ist lokal) | kurzlebige FB + Continuous Review |
| Release-Vorbereitung | `release/`-Branch, „gehärtet“ → `main` + Tag | `git flow release start/finish` | Release-Branch JIT aus Trunk **oder** direkt aus Trunk |
| Hotfix-Prozess (Merge-back) | `hotfix/` aus `main` → `main`+`develop` | vereinheitlichte `hotfix`-Befehle | fix-forward auf Trunk (bzw. cherry-pick) |
| Bug-/Fix-Branch-Logik | `hotfix/` ab `main`; `bugfix/` (ab `develop`) nur in Tools | `hotfix/` ab prod-Branch; `bugfix/` als Custom-Typ ab `develop` | fix-forward, kein eigener Branch-Typ |
| Change-Propagation | manuell per definierten Merges | **automatisch** (Dependency Tracking) | nicht nötig (ein Branch) |

---

## 3. Release & Versionierung

| Kriterium | klassisches git-flow | git-flow-next (Gitflow-Preset) | Trunk-Based Development |
|---|---|---|---|
| Unterstützte Release-Kadenz | geplant/getaktet, eher selten | wie Preset (geplant); je Konfiguration flexibel | kontinuierlich / on-demand möglich |
| Release-Quelle (woraus deployt wird) | `main` (nur getaggte Commits) | `main` (Preset) | direkt aus `trunk` **oder** JIT-Release-Branch |
| Versionierung / Tagging | Tag auf `main` bei `release finish` | Tag automatisiert bei `finish`; `tag=true` pro Typ konfigurierbar | Tag auf Trunk-Commit bzw. Release-Branch |
| Mehrere Versionen parallel pflegen | via `support/` (avh, experimentell) | via `support/`-Default-Typ | via Release-Branch pro Major (JIT, danach gelöscht) |
| Fix für ältere Releases | `hotfix` → `main` (+ Merge-back), ggf. `support` | `hotfix`/`support`, automatisiert | Cherry-pick auf Release-Branch bzw. fix-forward |
| „Release-freeze“ nötig? | ja (Härtung auf `release/`-Branch) | ja (Preset) | nein (Trunk bleibt releasable; Feature Flags) |

---

## 4. CI/CD-Eignung

| Kriterium | klassisches git-flow | git-flow-next (Gitflow-Preset) | Trunk-Based Development |
|---|---|---|---|
| Continuous Integration (echt) | schwach: lange FB + spätes Integrieren → nicht „continuous“ | wie Preset — Tool ändert die Integrationsfrequenz nicht | Kern-Enabler: alle ≥ 1×/Tag auf `trunk` |
| Continuous Delivery / Deployment | erschwert (mehrstufige Merge-backs; `main` nicht per se „always releasable“) | wie Preset | nativ: `trunk` stets auslieferbar |
| „Merge Hell“-Risiko | hoch (langlebige, divergierende Branches) | konzeptionell hoch; Auto-Merge mildert Propagation, nicht Feature-Divergenz | niedrig (kleine, häufige Integration) |
| Feature Flags / Branch by Abstraction | optional, untypisch | optional | oft erforderlich (unfertige Arbeit im Trunk verbergen) |
| Pipeline-Trigger-Branch(es) | `develop`, `release/*`, `main` | wie Preset (konfigurierbar) | `trunk` (+ ggf. Release-Branch) |
| Eignung für DevOps/Hochfrequenz | gering (bekannt schwierig mit CI/CD) | gering (Modell-bedingt) | hoch (von „Continuous Delivery“/„DevOps Handbook“ empfohlen) |

---

## 5. Komplexität

*kognitive und operative Last, um das Modell korrekt anzuwenden*

| Faktor | klassisches git-flow | git-flow-next (Gitflow-Preset) | Trunk-Based Development |
|---|---|---|---|
| 1. Branch-Typen & Regeln im Kopf | 5 Typen (`main`, `develop`, `feature`, `release`, `hotfix`) + optional `support`; feste Regel pro Typ | gleiche 5–6 Typen als Preset, aber Typen/Regeln *konfigurierbar* → mehr Optionen potenziell mehr zu durchdringen | 1 langlebiger Branch + kurzlebige FB; kaum Typ-Regeln |
| 2. Distinkte Merge-/Merge-back-Pfade | viele; v. a. Zwei-Ziel-Merge-backs (`release`/`hotfix` → `main` **und** `develop`), alle manuell → Hauptfehlerquelle | konzeptionell dieselben Pfade, aber automatisiert (Dependency Tracking) → operativ oft *ein* Befehl | im Kern 1 Pfad (→ `trunk`); Merge-back entfällt (fix-forward) |
| 3. Onboarding / Lernkurve | steil: viele Regeln plus richtige Reihenfolge | Modell gleich steil, Befehle aber vereinheitlicht/geführt → operativ flacher; *zusätzlich* Tool + Config lernen | Branch-Regeln flach; dafür müssen Disziplin, CI und Flags sitzen |
| 4. Verlagerte Komplexität (wohin sie wandert) | bleibt beim Entwickler (manuelle Merge-Disziplin) | wandert ins Tool + Konfiguration | wandert in Engineering-Praktiken: Feature Flags, Branch by Abstraction, starke CI, kurze Zyklen, schnelles Review |
| **Gesamteinordnung** | durchgängig hoch (konzeptionell + operativ) | konzeptionell hoch, operativ mittel (Automatisierung senkt nur die operative Hälfte) | strukturell niedrig, aber nicht eliminiert — verlagert in technische Disziplin |

> **Kernaussage:** Die Komplexität verschwindet bei keinem Modell — sie wechselt nur den **Ort**: Entwicklerkopf (git-flow) → Tool (git-flow-next) → Engineering-Praxis (TBD).

---

## 6. Tooling

*Hier setzt sich git-flow-next ab*

| Kriterium | klassisches git-flow | git-flow-next | Trunk-Based Development |
|---|---|---|---|
| Automatisierungsgrad | Original-CLI (start/finish) | hoch, vereinheitlichte Befehle | keine spezielle CLI (rohes git + PR-Plattform) |
| Auto-Merge abhängiger Branches | nein (manuell) | ja (Dependency Tracking) | n/a (ein Branch) |
| Konfigurierbarkeit | gering (feste Typen) | hoch (eigene Typen & Merge-Strategien) | per Konvention |
| Wartungs-/Pflegestatus | git-flow & gitflow-avh eingestellt | aktiv gepflegt, v1.1.0 | Praxis/kein Tool; Doku aktiv |
| IDE-/Editor-Integration | ältere Plugins | VS-Code-Extension, Tower | Standard-Git überall |

---

## Quellen

- git-flow-next – offizielle Website & Doku: <https://git-flow.sh/> (Gitflow-Workflow, Configuration, Commands)
- Trunk-Based Development: <https://trunkbaseddevelopment.com/>
- Atlassian Git Tutorial – Gitflow Workflow: <https://www.atlassian.com/git/tutorials/comparing-workflows/gitflow-workflow>
- Vincent Driessen, „A successful Git branching model“ (Original-git-flow, 2010)

*Stand: Juli 2026.*
