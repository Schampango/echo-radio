# Echo Radio

**Internetradio mit großen Senderknöpfen, lokaler Sprachsteuerung und Weboberfläche –
für den Amazon Echo Show 8 (1. Gen.) mit LineageOS.**

Echo Radio macht aus einem entsperrten Echo Show 8 ein schlichtes, werbefreies Küchen- oder
Wohnzimmerradio: Knopf antippen oder „Alexa, spiel Deutschlandfunk“ sagen, fertig.
Ohne Amazon-Konto, ohne Cloud. Die Spracherkennung läuft vollständig auf dem Gerät.

📖 **[Installationsanleitung](docs/INSTALLATION.md)** · **[Bedienungsanleitung](docs/BEDIENUNGSANLEITUNG.md)**

---

## Funktionen

**Radio und Podcasts**
- Frei belegbare, runde Knöpfe mit Farbe oder Senderlogo; mehrere Seiten zum Wischen
- Radio-Streams (auch reine AAC-Streams), Sendersuche per Name über radio-browser.info
- „Neueste Folge“ aus Podcast-Feeds, z. B. aktuelle Nachrichten, mit Fortsetzen an der gemerkten Stelle
  und Fortschrittsbalken
- Knöpfe, die andere Apps oder Webseiten öffnen (z. B. Apple Music, NewPipe)
- Interpret und Titel des laufenden Songs
- Automatisches Neuverbinden bei WLAN-Aussetzern, sanfter Senderwechsel ohne Knacken
- Equalizer mit Voreinstellungen

**Sprachsteuerung (offline)**
- „Alexa, spiel …“, „Alexa, Stopp“, „Alexa, lauter/leiser“, „Alexa, Lautstärke 5“
- Erkennung mit [Vosk](https://alphacephei.com/vosk/) direkt auf dem Echo, kein Internet nötig
- Pulsierender roter Rand, solange zugehört wird, grün oder orange als Rückmeldung
- Alle Wörter änderbar, auch das Aktivierungswort; tolerante Erkennung von Sendernamen

**Display**
- Bleibt an, dunkelt nach einstellbarer Zeit ab und zeigt dann eine Uhr mit Sender, Interpret und Titel
- Aufwecken per Berührung oder Sprache
- Läuft als Startbildschirm; holt sich auf Wunsch aus anderen Apps wieder nach vorne

**Weboberfläche** (`http://<IP des Echo>:8080`)
- Knöpfe anlegen, bearbeiten, per Drag & Drop sortieren, Logos suchen
- Fernbedienung: abspielen, stoppen, Lautstärke
- Sprachbefehle, Equalizer und Display einstellen
- Sicherung herunterladen und wiederherstellen

## Voraussetzungen

- Amazon Echo Show 8, **1. Generation (2019, „crown“)**, entsperrt, mit **LineageOS 18.1**.
  Die Anleitung dazu steht in der [Installationsanleitung](docs/INSTALLATION.md).
- Alternativ jedes Android-Gerät ab **Android 8** im Querformat
- WLAN; einmalig ca. 45 MB Download für das Sprachmodell

## Schnellstart

```bash
git clone https://github.com/Schampango/echo-radio.git
cd echo-radio
./build.sh                                   # Linux Mint/Ubuntu, als root
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell pm grant de.polla.echoradio android.permission.RECORD_AUDIO
adb shell appops set de.polla.echoradio SYSTEM_ALERT_WINDOW allow
adb shell cmd package set-home-activity de.polla.echoradio/.MainActivity
adb shell am start -n de.polla.echoradio/.MainActivity
```

Die Erklärung zu jedem Schritt steht in der [Installationsanleitung](docs/INSTALLATION.md).

## Technik

| | |
|---|---|
| Sprache | Java, ohne UI-Framework (reine Android-Views) |
| Android | minSdk 26, targetSdk 30, compileSdk 34 |
| Wiedergabe | [Media3 ExoPlayer](https://developer.android.com/media/media3/exoplayer) 1.4 |
| Spracherkennung | [Vosk](https://alphacephei.com/vosk/) 0.3.47 mit `vosk-model-small-de-0.15` (zur Laufzeit geladen) |
| Senderdaten | [radio-browser.info](https://www.radio-browser.info) |
| Weboberfläche | eingebauter HTTP-Server (Port 8080), eine HTML-Datei ohne externe Abhängigkeiten |

Paketname: `de.polla.echoradio` · Protokoll: `adb logcat -s EchoRadio:*`

## Hinweise

- Dieses Projekt steht in keiner Verbindung zu Amazon. „Alexa“ und „Echo“ sind Marken von Amazon.
  „Alexa“ wird hier nur als voreingestelltes, änderbares Aktivierungswort verwendet.
- Das Entsperren des Echo ersetzt die Amazon-Software, beendet die Garantie und kann das Gerät
  unbrauchbar machen. Du handelst auf eigene Gefahr.
- Die Weboberfläche hat kein Passwort. Nutze sie nur im eigenen Heimnetz.

## Lizenz

Copyright (C) 2026 Schampango

Echo Radio ist freie Software: Du darfst sie unter den Bedingungen der
**GNU General Public License, Version 3** (oder nach deiner Wahl einer späteren Version),
wie von der Free Software Foundation veröffentlicht, weitergeben und verändern.
Wer eine veränderte Fassung weitergibt, muss deren Quellcode ebenfalls unter der GPL offenlegen.

Die Software wird ohne jede Gewährleistung bereitgestellt. Den vollständigen Text findest du in [LICENSE](LICENSE).

Verwendete Bibliotheken behalten ihre eigenen Lizenzen: Media3 ExoPlayer und Vosk (Apache 2.0),
JNA (Apache 2.0/LGPL), das Sprachmodell `vosk-model-small-de-0.15` (Apache 2.0).

## Dank

- **R0rt1z2 (Rortiz2)** für amonet und das Entsperren der Echo-Geräte
- **bengris32** für LineageOS auf dem Echo Show
- **Alpha Cephei** für Vosk und die freien Sprachmodelle
- **radio-browser.info** für das offene Senderverzeichnis
