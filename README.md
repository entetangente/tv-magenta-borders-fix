# EdgeFix – Fire-TV-Overlay gegen lila Bildränder

Android-App für den Fire TV (Fire OS 6–8). Sie legt einen dauerhaften, halbtransparenten
Farbverlauf über die Bildränder, damit die lila Verfärbung alternder Quantum-Dot-Panels
bei hellen Inhalten weniger auffällt. Die Bildmitte bleibt bitgenau unverändert.

**Stand: Stufe 2**
- Overlay-Dienst mit parametrischer Randmaske und Dithering
- TV-Oberfläche: Startbildschirm, Feinabstimmung mit Testbildern und Vorher/Nachher, Einstellungen
- Ersteinrichtung mit Selbst-Freischaltung der Overlay-Berechtigung über ADB (kein PC nötig)
- Autostart nach dem Einschalten und nach App-Updates
- **Kalibrierung mit dem Handy:** QR-Code scannen, 5 Fotos, die Web-App berechnet eine
  Korrekturkarte (160×90) und schickt sie an den Fernseher. Vorher/Nachher, Stärke,
  Schutz für dunkle Szenen, optionales Nachmessen (geschlossener Regelkreis).

Folgt: Profile SDR/HDR, Abgleich per Auge, Schnellumschaltung (Stufe 3).

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
- `measure.ts`: Homografie, 160×90-Raster, Median je Zelle, Fotoprüfung
- `model.ts`: Panel-Modell g je Zelle und Stufe relativ zur Bildmitte desselben Fotos,
  Farbabstand D (ΔE2000, Helligkeit herausgerechnet), Rauschgrenze t0 aus der Bildmitte,
  Betroffenheitsmaske M = smoothstep(t0, 2·t0, D)
- `solve.ts`: gemeinsame Optimierung von Deckkraft a und vormultiplizierter Farbe a·O für
  alle Zellen (Adam, projiziert). Zielfehler getrennt nach Farbe und Helligkeit (Farbe zählt
  voll, Helligkeit 10 %), helle Stufen stärker gewichtet (L²), Glattheitsterm,
  Schwarz-Schutz λ. Danach Gauß-Glättung, a = M·a, Steigungsgrenze 0.01 je Zelle,
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

## Installieren

### Ohne PC (Downloader-App)
Funktioniert nur, wenn dieses Repository öffentlich ist.

1. Am Fire TV: Einstellungen → Mein Fire TV → Info → siebenmal auf den Gerätenamen klicken.
2. Entwickleroptionen → „Apps unbekannter Herkunft“ für die App **Downloader** erlauben.
3. In Downloader diese Adresse eingeben:
   `https://github.com/entetangente/tv-magenta-borders-fix/releases/latest/download/edgefix.apk`
4. Installieren, EdgeFix öffnen und dem Einrichtungsassistenten folgen.

### Mit PC
```bash
adb connect <IP-des-Fire-TV>:5555
adb install -r edgefix.apk
```
Falls die Selbst-Freischaltung nicht klappt, die Berechtigung vom PC aus setzen:
```bash
adb shell appops set de.edgefix SYSTEM_ALERT_WINDOW allow
```

## Wichtig am Samsung-TV
Bildgröße **16:9 Standard** und **„An Bildschirm anpassen“** einschalten. Sonst schneidet
der Fernseher die Ränder per Overscan ab, und das Overlay sitzt nicht an den Panelkanten.

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
- Jetpack Compose for TV (tv-material), dadb für die ADB-Selbstfreischaltung,
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
- `./gradlew :app:testDebugUnitTest` – Tests für Maske, Dithering und ADB-Schlüssel;
  schreibt eine Vorschau nach `app/build/preview/`
- `./gradlew :app:assembleRelease` – APK

Die APK wird mit `app/edgefix-shared.keystore` signiert. Dieser Schlüssel liegt bewusst im
Repository, damit jeder Build (lokal oder GitHub Actions) über die vorherige Version
installiert werden kann. Er ist nicht geheim und nur für die private Nutzung gedacht.

GitHub Actions baut bei jedem Push auf `main`, führt die Tests aus und veröffentlicht die
APK als Release.
