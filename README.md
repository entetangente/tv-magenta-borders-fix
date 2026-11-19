# EdgeFix – Fire-TV-Overlay gegen lila Bildränder

Android-App für den Fire TV (Fire OS 6–8). Sie legt einen dauerhaften, halbtransparenten
Farbverlauf über die Bildränder, damit die lila Verfärbung alternder Quantum-Dot-Panels
bei hellen Inhalten weniger auffällt. Die Bildmitte bleibt bitgenau unverändert.

**Stand: Stufe 1 (MVP)**
- Overlay-Dienst mit parametrischer Randmaske und Dithering
- TV-Oberfläche: Startbildschirm, Feinabstimmung mit Testbildern und Vorher/Nachher, Einstellungen
- Ersteinrichtung mit Selbst-Freischaltung der Overlay-Berechtigung über ADB (kein PC nötig)
- Autostart nach dem Einschalten und nach App-Updates

Folgt: Kalibrierung per Handy-Kamera (Stufe 2), Profile SDR/HDR (Stufe 3).

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
- Jetpack Compose for TV (tv-material), dadb für die ADB-Selbstfreischaltung
- `./gradlew :app:testDebugUnitTest` – Tests für Maske, Dithering und ADB-Schlüssel;
  schreibt eine Vorschau nach `app/build/preview/`
- `./gradlew :app:assembleRelease` – APK

Die APK wird mit `app/edgefix-shared.keystore` signiert. Dieser Schlüssel liegt bewusst im
Repository, damit jeder Build (lokal oder GitHub Actions) über die vorherige Version
installiert werden kann. Er ist nicht geheim und nur für die private Nutzung gedacht.

GitHub Actions baut bei jedem Push auf `main`, führt die Tests aus und veröffentlicht die
APK als Release.
