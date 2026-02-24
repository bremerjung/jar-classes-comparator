/**
 * Verifikations-Skript für IT-Szenario 01: Geänderte Datei
 *
 * Erwartet:
 *   - target/hotpatch.zip existiert
 *   - Das ZIP enthält genau einen Eintrag: com/example/Foo.class
 */
import java.util.zip.ZipFile

// ── 1. Build muss erfolgreich gewesen sein ──────────────────────────────────
assert new File(basedir, 'target').exists() : 'target/ fehlt – Build hat nicht stattgefunden'

// ── 2. hotpatch.zip muss existieren ─────────────────────────────────────────
File zip = new File(basedir, 'target/hotpatch.zip')
assert zip.exists() : "hotpatch.zip wurde nicht erstellt: ${zip.absolutePath}"

// ── 3. ZIP-Inhalt prüfen ────────────────────────────────────────────────────
List<String> entries = []
new ZipFile(zip).withCloseable { zf ->
    zf.entries().each { entries << it.name }
}

assert entries.size() == 1 :
    "Erwartet genau 1 Eintrag im ZIP, gefunden: ${entries.size()} → ${entries}"

assert entries.contains('com/example/Foo.class') :
    "com/example/Foo.class fehlt im ZIP. Gefundene Einträge: ${entries}"

println "[IT-01 ✓] hotpatch.zip enthält korrekt: ${entries}"
