#!/usr/bin/env bash
#
# cherry-pick-multi.sh
# Automatisiert das Cherry-Picken von Commits auf mehrere Branches.
# Wird von Claude aufgerufen — Konfliktlösung erfolgt interaktiv.
#
# Verwendung:
#   cherry-pick-multi.sh --commits "sha1 sha2 ..." --branches "branch1 branch2 ..." [--repo /pfad] [--report-dir /pfad]
#
# Exit-Codes:
#   0 = alle erfolgreich
#   1 = einige Fehler (siehe Bericht)
#   2 = ungültige Eingabe / Umgebungsfehler

set -euo pipefail

# ── Standardwerte ─────────────────────────────────────────────
COMMITS=""
BRANCHES=""
REPO_DIR="."
REPORT_DIR="."
TIMESTAMP=$(date +"%Y-%m-%d_%H-%M-%S")
REPORT_FILE=""
HAD_FAILURES=0

# ── Farben ────────────────────────────────────────────────────
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # Keine Farbe

# ── Argumente parsen ──────────────────────────────────────────
while [[ $# -gt 0 ]]; do
    case "$1" in
        --commits)    COMMITS="$2";    shift 2 ;;
        --branches)   BRANCHES="$2";   shift 2 ;;
        --repo)       REPO_DIR="$2";   shift 2 ;;
        --report-dir) REPORT_DIR="$2"; shift 2 ;;
        *) echo "Unbekannte Option: $1"; exit 2 ;;
    esac
done

if [[ -z "$COMMITS" || -z "$BRANCHES" ]]; then
    echo "Verwendung: $0 --commits 'sha1 sha2' --branches 'branch1 branch2' [--repo pfad] [--report-dir pfad]"
    exit 2
fi

read -ra COMMIT_ARRAY <<< "$COMMITS"
read -ra BRANCH_ARRAY <<< "$BRANCHES"

# ── Vorbereitung ──────────────────────────────────────────────
cd "$REPO_DIR"

if ! git rev-parse --is-inside-work-tree &>/dev/null; then
    echo -e "${RED}Fehler: Kein Git-Repository: $REPO_DIR${NC}"
    exit 2
fi

# Sauberen Arbeitsbaum prüfen
if [[ -n "$(git status --porcelain)" ]]; then
    echo -e "${YELLOW}Warnung: Arbeitsbaum ist unsauber. Bitte erst stashen oder committen.${NC}"
    git status --short
    exit 2
fi

# Aktuellen Branch speichern
ORIGINAL_BRANCH=$(git symbolic-ref --short HEAD 2>/dev/null || git rev-parse HEAD)

# Commits validieren
for sha in "${COMMIT_ARRAY[@]}"; do
    if ! git cat-file -t "$sha" &>/dev/null; then
        echo -e "${RED}Fehler: Commit nicht gefunden: $sha${NC}"
        exit 2
    fi
done

# Branches validieren
for branch in "${BRANCH_ARRAY[@]}"; do
    if ! git rev-parse --verify "$branch" &>/dev/null; then
        echo -e "${RED}Fehler: Branch nicht gefunden: $branch${NC}"
        exit 2
    fi
done

# ── Bericht-Vorbereitung ─────────────────────────────────────
REPORT_FILE="${REPORT_DIR}/cherry-pick-bericht-${TIMESTAMP}.md"

declare -a RESULTS=()  # "commit|branch|status|details"

log_result() {
    local commit="$1" branch="$2" status="$3" details="${4:-}"
    RESULTS+=("${commit}|${branch}|${status}|${details}")
}

# ── Cherry-Pick-Schleife ─────────────────────────────────────
for branch in "${BRANCH_ARRAY[@]}"; do
    echo -e "\n${BLUE}━━━ Wechsle zu Branch: $branch ━━━${NC}"
    git checkout "$branch" 2>/dev/null

    for sha in "${COMMIT_ARRAY[@]}"; do
        short_sha="${sha:0:7}"
        commit_msg=$(git log --format='%s' -1 "$sha" 2>/dev/null || echo "unbekannt")
        echo -e "  Cherry-Picke ${short_sha} (${commit_msg})..."

        # Cherry-Pick versuchen
        if git cherry-pick "$sha" --no-commit 2>/dev/null; then
            # Prüfen ob es etwas zu committen gibt
            if [[ -z "$(git diff --cached --name-only)" ]]; then
                echo -e "  ${YELLOW}⏭️  Bereits vorhanden — wird übersprungen${NC}"
                git cherry-pick --abort 2>/dev/null || true
                log_result "$short_sha" "$branch" "ÜBERSPRUNGEN" "Bereits vorhanden"
            else
                # Konfliktmarker in gestagten Dateien prüfen
                CONFLICT_FILES=$(git diff --cached --name-only | xargs grep -l '<<<<<<<' 2>/dev/null || true)
                if [[ -n "$CONFLICT_FILES" ]]; then
                    echo -e "  ${YELLOW}⚠️  Konflikte erkannt in: $CONFLICT_FILES${NC}"
                    echo "  KONFLIKT_MUSS_GELÖST_WERDEN"
                    # Nicht committen — gestagt lassen für Claude zur Inspektion & Lösung
                    log_result "$short_sha" "$branch" "KONFLIKT" "$CONFLICT_FILES"
                    HAD_FAILURES=1
                    git cherry-pick --abort 2>/dev/null || true
                else
                    git commit -C "$sha" --no-edit 2>/dev/null
                    echo -e "  ${GREEN}✅ Erfolg${NC}"
                    log_result "$short_sha" "$branch" "ERFOLG" ""
                fi
            fi
        else
            # Cherry-Pick selbst fehlgeschlagen — wahrscheinlich ein Konflikt
            CONFLICT_FILES=$(git diff --name-only --diff-filter=U 2>/dev/null || true)
            if [[ -n "$CONFLICT_FILES" ]]; then
                echo -e "  ${RED}❌ Konflikt: $CONFLICT_FILES${NC}"
                echo "  KONFLIKT_MUSS_GELÖST_WERDEN"
                log_result "$short_sha" "$branch" "KONFLIKT" "$CONFLICT_FILES"
                HAD_FAILURES=1
                git cherry-pick --abort 2>/dev/null || true
            else
                echo -e "  ${RED}❌ Fehlgeschlagen (unbekannter Grund)${NC}"
                log_result "$short_sha" "$branch" "FEHLGESCHLAGEN" "Unbekannter Fehler"
                HAD_FAILURES=1
                git cherry-pick --abort 2>/dev/null || true
            fi
        fi
    done
done

# ── Zum ursprünglichen Branch zurückkehren ────────────────────
echo -e "\n${BLUE}Kehre zurück zu Branch: $ORIGINAL_BRANCH${NC}"
git checkout "$ORIGINAL_BRANCH" 2>/dev/null

# ── Terminal-Bericht ──────────────────────────────────────────
echo ""
echo "╔══════════════════════════════════════════════════════════════════════╗"
echo "║              Cherry-Pick-Bericht — $TIMESTAMP               ║"
echo "╠══════════════════════════════════════════════════════════════════════╣"
printf "║ %-15s │ %-20s │ %-25s ║\n" "Commit" "Branch" "Status"
echo "╠═════════════════╪══════════════════════╪═══════════════════════════╣"

CNT_ERFOLG=0
CNT_SKIP=0
CNT_KONFLIKT=0
CNT_FEHLER=0

for result in "${RESULTS[@]}"; do
    IFS='|' read -r commit branch status details <<< "$result"
    case "$status" in
        ERFOLG)          icon="✅ ERFOLG";          CNT_ERFOLG=$((CNT_ERFOLG+1))      ;;
        ÜBERSPRUNGEN)    icon="⏭️  ÜBERSPRUNGEN";    CNT_SKIP=$((CNT_SKIP+1))          ;;
        KONFLIKT)        icon="❌ KONFLIKT";         CNT_KONFLIKT=$((CNT_KONFLIKT+1))  ;;
        AUTOM_GELÖST)    icon="⚠️  AUTOM. GELÖST";   CNT_ERFOLG=$((CNT_ERFOLG+1))      ;;
        *)               icon="❌ FEHLGESCHLAGEN";   CNT_FEHLER=$((CNT_FEHLER+1))       ;;
    esac
    printf "║ %-15s │ %-20s │ %-25s ║\n" "$commit" "$branch" "$icon"
done

echo "╚══════════════════════════════════════════════════════════════════════╝"
echo ""
echo "Zusammenfassung: $CNT_ERFOLG erfolgreich, $CNT_SKIP übersprungen, $CNT_KONFLIKT Konflikte, $CNT_FEHLER fehlgeschlagen"

# ── Markdown-Bericht ──────────────────────────────────────────
{
    echo "# Cherry-Pick-Bericht"
    echo ""
    echo "**Datum:** $(date '+%Y-%m-%d %H:%M:%S')"
    echo "**Repository:** $(pwd)"
    echo "**Ursprünglicher Branch:** $ORIGINAL_BRANCH"
    echo ""
    echo "## Commits"
    echo ""
    for sha in "${COMMIT_ARRAY[@]}"; do
        msg=$(git log --format='%s' -1 "$sha" 2>/dev/null || echo "unbekannt")
        echo "- \`${sha:0:7}\` — $msg"
    done
    echo ""
    echo "## Zielbranches"
    echo ""
    for branch in "${BRANCH_ARRAY[@]}"; do
        echo "- \`$branch\`"
    done
    echo ""
    echo "## Ergebnisse"
    echo ""
    echo "| Commit | Branch | Status | Details |"
    echo "|--------|--------|--------|---------|"
    for result in "${RESULTS[@]}"; do
        IFS='|' read -r commit branch status details <<< "$result"
        echo "| \`$commit\` | \`$branch\` | **$status** | $details |"
    done
    echo ""
    echo "## Zusammenfassung"
    echo ""
    echo "- **Erfolgreich:** $CNT_ERFOLG"
    echo "- **Übersprungen:** $CNT_SKIP"
    echo "- **Konflikte:** $CNT_KONFLIKT"
    echo "- **Fehlgeschlagen:** $CNT_FEHLER"
} > "$REPORT_FILE"

echo ""
echo -e "${GREEN}Markdown-Bericht gespeichert unter: $REPORT_FILE${NC}"

# ── Exit-Code ─────────────────────────────────────────────────
if [[ $HAD_FAILURES -eq 1 ]]; then
    exit 1
else
    exit 0
fi