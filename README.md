# EdgeFix – Fire-TV-Overlay gegen lila Bildränder

Android-App für den Fire TV (Fire OS 6–8). Sie legt einen dauerhaften, halbtransparenten
Farbverlauf über die Bildränder, damit die lila Verfärbung alternder Quantum-Dot-Panels
bei hellen Inhalten weniger auffällt. Die Bildmitte bleibt bitgenau unverändert.

**Stand: Stufe 3**
- Overlay-Dienst mit Autostart (nach Einschalten, Standby und App-Update)
- **Kalibrierung mit dem Handy:** QR-Code, 5 Fotos, Korrekturkarte 384×216, Nachmessen
- **Feinabstimmung am Fernseher:** Stärke, Schutz für dunkle Szenen, Testbilder, Vorher/Nachher
- **Abgleich per Auge ohne Handy:** Rand an 16 Punkten je Kante nachzeichnen (Breite, Stärke),
  Farbton an 8 Stellen mit einem Vergleichsfeld angleichen
- **Profile SDR / HDR / Aus** mit eigenen Werten, manuell umschaltbar
- **Mehrere gespeicherte Kalibrierungen** je Profil wählbar, löschbar
- **Schnellumschaltung:** Menü-Taste lange drücken schaltet die Korrektur ein/aus
- Diagnose-Seite (Berechtigung, Dienst, Overlay-Fenster, roter Testrahmen)

Folgt (Stufe 4): automatische HDR-Erkennung, falls Fire OS sie zuverlässig meldet.

## Einrichtung

### 1. Installieren (ohne PC, Downloader-App)
1. Am Fire TV: Einstellungen → Mein Fire TV → Info → siebenmal auf den Gerätenamen klicken.
2. Entwickleroptionen → „Apps unbekannter Herkunft“ für **Downloader** erlauben.
3. In Downloader:
   `https://github.com/entetangente/tv-magenta-borders-fix/releases/latest/download/edgefix.apk`

Updates installiert man über dieselbe Adresse; Einstellungen und Berechtigung bleiben erhalten.

### 2. Overlay-Berechtigung
Fire OS hat keinen funktionierenden Dialog für „Über anderen Apps anzeigen“. Die App
versucht, sich die Berechtigung über den eigenen ADB-Dienst selbst zu geben
(Entwickleroptionen → ADB-Debugging an, dann „Jetzt einrichten“).

**Fire OS 8 (z. B. Fire TV Stick 4K Max) trennt solche Verbindungen sofort.** Dort geht es
nur einmalig über einen PC im selben WLAN:
```bash
adb connect <IP-des-Fire-TV>:5555
adb shell appops set de.edgefix SYSTEM_ALERT_WINDOW allow
```
adb gibt es z. B. per `brew install --cask android-platform-tools` (macOS) oder als
[Platform Tools](https://developer.android.com/tools/releases/platform-tools). Die App zeigt die
Befehle mit der richtigen IP-Adresse an. Danach darf ADB-Debugging wieder aus.

### 3. Schnellumschaltung (optional)
Die Bedienungshilfe „EdgeFix Schnellumschaltung“ einschalten (Fire-TV-Einstellungen →
Bedienungshilfen) oder per PC:
```bash
adb shell settings put secure enabled_accessibility_services de.edgefix/de.edgefix.quick.QuickToggleService
adb shell settings put secure accessibility_enabled 1
```
Achtung: Der erste Befehl ersetzt andere aktive Bedienungshilfen. Der Dienst liest keine
Bildschirminhalte, er wertet nur die Menü-Taste aus.

### Wichtig am Samsung-TV
Bildgröße **16:9 Standard** und **„An Bildschirm anpassen“** einschalten. Sonst schneidet
der Fernseher die Ränder per Overscan ab, und das Overlay sitzt nicht an den Panelkanten.

## Profile
SDR und HDR haben eigene Werte (Randmaske, Abgleich per Auge, Karte, Stärke), weil das
Overlay bei HDR anders wirkt. Umschalten auf dem Startbildschirm oder in den
Einstellungen; „Aus“ pausiert das Overlay. Eine Kalibrierung landet im gerade aktiven
Profil. „Werte nach HDR kopieren“ gibt einen Startpunkt für das andere Profil.

## Abgleich per Auge
Für alle ohne Handy-Kalibrierung, oder zum Nachbessern der Randmaske:
- **Rand nachzeichnen:** ◀ ▶ wählt einen von 64 Punkten (16 je Kante, im Uhrzeigersinn),
  ▲ ▼ ändert Breite oder Stärke, OK wechselt zwischen beiden, ⏯ wechselt das Testbild.
  Zwischen den Punkten wird per Spline interpoliert.
- **Farbton angleichen:** An 8 Stellen (Ecken, Kantenmitten) ein Vergleichsfeld per
  ◀ ▶ (Farbton) und ▲ ▼ (Tönung) so einstellen, dass es genauso lila aussieht wie der Rand.
  Die Gegenfarbe wird daraus berechnet; stärker getönte Stellen werden stärker korrigiert.

## Kalibrierung mit dem Handy

1. Am Fire TV „Mit Handy kalibrieren“ öffnen. Der Fernseher startet einen lokalen Server
   (Port 8765, nur solange der Bildschirm offen ist) und zeigt einen QR-Code mit einem
   zufälligen Zugangscode.
2. QR-Code mit der Handykamera scannen. Handy und Fire TV müssen im selben WLAN sein
   (kein Gast-WLAN, keine Client-Isolation).
3. Die Web-App führt durch 5 Fotos von Graubildern (75, 50, 35, 25, 15 %). Jedes Foto wird
   sofort geprüft (Bildschirm vollständig, Abstand, Weitwinkel, Schärfe, Belichtung).
4. Das Handy berechnet die Karte (Web Worker) und schickt sie an den Fernseher. Die Fotos
   verlassen das Handy nicht.

### Auswertung (web/src/analysis)
- `detect.ts`: Bildschirm finden (Otsu-Schwelle, größte helle Fläche, Kantenpunkte entlang
  der Normalen, RANSAC-Geraden, Durchbiegung als Weitwinkel-Test, Kantenbreite als Schärfetest)
- `measure.ts`: Homografie, 384×216-Raster, Median je Zelle, Fotoprüfung
- `model.ts`: Panel-Modell g je Zelle und Stufe relativ zur Bildmitte desselben Fotos,
  Farbabstand D (ΔE2000, Helligkeit herausgerechnet), Rauschgrenze t0 aus der Bildmitte,
  Betroffenheitsmaske M = smoothstep(t0, 2·t0, D)
- `solve.ts`: gemeinsame Optimierung von Deckkraft a und vormultiplizierter Farbe a·O für
  alle Zellen (Adam, projiziert). Zielfehler getrennt nach Farbe und Helligkeit (Farbe zählt
  voll, Helligkeit 10 %), helle Stufen stärker gewichtet (L²), Glattheitsterm,
  Schwarz-Schutz λ. Danach Gauß-Glättung, a = M·a, Steigungsgrenze 0,06 je Zelle,
  Überkorrektur-Sperre, Werte < 0.5/255 exakt 0
- `refine.ts`: Nachmessen mit Overlay, Modellkorrektur aus Messung/Vorhersage,
  Abnahmeprüfungen (Überkorrektur, Kanten). Nicht bestanden → Stärke wird verringert
- `mapfile.ts`: Kartenformat (siehe unten)

### Kartenformat
Little Endian: `"EFM1" | u16 gw | u16 gh | u8 Varianten | 3 Byte frei`, danach je Variante
gw·gh × (f32 a, f32 a·O_r, f32 a·O_g, f32 a·O_b). Drei Varianten mit niedrigem, mittlerem
und hohem Schwarz-Schutz. Gleitkommazahlen statt 16-Bit-PNG, weil Android 7.1 16-Bit-PNGs
beim Dekodieren auf 8 Bit kürzt.

### Grenzen
Ein Overlay kann nur mischen. Ein Ausgleich, der bei Weiß voll wirkt, würde dunkle
Szenen grünlich färben. Die Berechnung lässt deshalb lieber einen Rest-Lilaton stehen,
als in dunklen Szenen über die Mitte hinaus zu korrigieren.

## Wie die Maske berechnet wird
Für jede Kante e mit Abstand d_e zur Kante, Breite w_e und Stärke s_e:

```
a_e = min(1, a_max · s_e · f(max(0, 1 − d_e / w_e)))
a   = 1 − (1 − a_oben)(1 − a_unten)(1 − a_links)(1 − a_rechts)
O   = k · H          (H = Gegenfarbe aus dem Farbton, k = Farbanteil)
```

f ist die Verlaufskurve (linear, smoothstep, quadratisch). Außerhalb der Randbreiten ist a
exakt 0. Das Bitmap wird nur bei Parameteränderungen neu berechnet (1080p: einige zehn ms).
Werte unter einem halben 8-Bit-Schritt werden auf 0 gesetzt, Dithering (Bayer 8×8) gibt es
nur, wo a > 0.

In der Oberfläche heißen die Werte „Stärke“ (a_max) und „Schutz für dunkle Szenen“ (1 − k).

## Entwicklung
- Kotlin, AGP 8.13, compileSdk 36, minSdk 25, targetSdk 34
- Jetpack Compose for TV (tv-material), eigener kleiner ADB-Client für die Selbstfreischaltung,
  NanoHTTPD (HTTP + WebSocket, läuft auch unter Android 7.1), ZXing für den QR-Code
- Web-App: TypeScript + Vite ohne Framework, Ausgabe nach `app/src/main/assets/web`
  (nicht im Repository, wird gebaut):
  ```bash
  cd web && npm ci && npm run build && npm test
  ```
  `npm test` enthält einen Ende-zu-Ende-Test im Browser (Playwright) gegen einen
  nachgebauten Fire TV, der synthetische Fotos eines Panels mit lila Rand liefert.
  Ohne Playwright wird dieser Test übersprungen.
- **Die Web-App muss vor dem Android-Build gebaut sein**, sonst fehlt die Kalibrierseite.
- `./gradlew :app:testDebugUnitTest` – Tests für Maske, Detailform, Karte, Einstellungen,
  ADB-Client (gegen einen nachgebauten adbd); schreibt eine Vorschau nach `app/build/preview/`
- `./gradlew :app:recordPaparazziDebug` – Bildschirmfotos aller TV-Bildschirme in
  1920×1080 nach `app/src/test/snapshots/images` (Layoutprüfung ohne Gerät)
- `./gradlew :app:assembleRelease` – APK

Die APK wird mit `app/edgefix-shared.keystore` signiert. Dieser Schlüssel liegt bewusst im
Repository, damit jeder Build (lokal oder GitHub Actions) über die vorherige Version
installiert werden kann. Er ist nicht geheim und nur für die private Nutzung gedacht.

GitHub Actions baut bei jedem Push auf `main`, führt die Tests aus und veröffentlicht die
APK als Release.

## Abweichungen von der Spezifikation
- **NanoHTTPD statt Ktor** für den lokalen Server: läuft zuverlässig ab Android 7.1.
- **Kartenformat EFM1** (Float32) statt 16-Bit-PNG: Android 7.1 kürzt 16-Bit-PNGs auf 8 Bit.
- **Raster 384×216 statt 160×90** und Steigungsgrenze 0,06 je Zelle statt 0,01: Echte Ränder
  sind oft nur 1–2 % der Bildhöhe breit; mit dem groben Raster blieb die Korrektur wirkungslos.
- **Optimierung in TypeScript auf dem Handy** statt scipy: kein PC nötig.
- **Zielfunktion:** Farbfehler zählt voll, Helligkeitsfehler 10 %; die drei Stufen
  „Schutz für dunkle Szenen“ wählen unterschiedliche Kompromisse (Gewichtung heller
  Stufen, ab welcher Graustufe die Überkorrektur-Sperre greift, Schwarz-Schutz λ).
- **Startbreite der Randmaske 2 %** statt 6 %, aus demselben Grund wie beim Raster.
