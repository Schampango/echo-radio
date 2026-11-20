# Installationsanleitung

Diese Anleitung führt dich vom Echo Show 8 mit Amazon-Software bis zur fertig eingerichteten
Echo-Radio-App. Sie besteht aus vier Teilen:

1. [Voraussetzungen](#1-voraussetzungen)
2. [Echo Show 8 entsperren und LineageOS installieren](#2-echo-show-8-entsperren-und-lineageos-installieren)
3. [Echo Radio installieren](#3-echo-radio-installieren)
4. [Einrichten](#4-einrichten)

Danach: [Aktualisieren](#5-aktualisieren) · [Deinstallieren](#6-deinstallieren) · [Fehlerbehebung](#7-fehlerbehebung)

> Läuft auf deinem Gerät schon Android 8 oder neuer (z. B. ein normales Tablet), überspringe Teil 2.
> Die App ist für den Echo Show 8 gebaut, funktioniert aber auf jedem Android-Gerät mit Querformat.

---

## 1. Voraussetzungen

**Gerät**

- Amazon Echo Show 8, **1. Generation (2019)**, Codename *crown*, Modell C7H6N3, mit Micro-USB-Buchse.
  Spätere Generationen lassen sich derzeit nicht entsperren.
- Ein USB-Kabel (Micro-USB auf USB-A), das Daten überträgt, nicht nur Strom.

**Computer**

- Zum **Entsperren** (Teil 2): Linux wird von den Entwicklern empfohlen
  (z. B. Ubuntu oder Linux Mint, auch als Live-USB-Stick).
- Zum **Installieren der App** (Teil 3): Linux, Windows oder macOS mit **adb**
  (Teil der [Android Platform Tools](https://developer.android.com/tools/releases/platform-tools)).
  - Ubuntu/Mint: `apt install adb`
  - Windows/macOS: Platform Tools herunterladen, entpacken, im Ordner ein Terminal öffnen.
- Zum **Selbstbauen** der App (optional): Linux Mint oder Ubuntu, ca. 2 GB freier Platz.

**Netz**

- WLAN mit Internetzugang (für Radiostreams und den einmaligen Download des Sprachmodells, ca. 45 MB).

> ⚠️ **Wichtig:** Das Entsperren ersetzt die Amazon-Software vollständig. Alexa, Amazon-Konto und
> Garantie sind danach weg. Ein Fehler beim Entsperren kann das Gerät unbrauchbar machen.
> Lies die verlinkten XDA-Anleitungen vollständig, bevor du anfängst. Du machst das auf eigene Gefahr.

---

## 2. Echo Show 8 entsperren und LineageOS installieren

Die Werkzeuge dafür stammen nicht aus diesem Projekt. Die maßgeblichen und aktuellen Anleitungen
stehen im XDA-Forum:

- **Entsperren, Root, TWRP:**
  [Unlock/Root/TWRP/Unbrick – Amazon Echo Show 8 1st Gen (2019, crown)](https://xdaforums.com/t/unlock-root-twrp-unbrick-amazon-echo-show-8-1st-gen-2019-crown.4766687/)
- **LineageOS 18.1 (Android 11):**
  [ROM – LineageOS 18.1 for the Amazon Echo Show 8 (2019)](https://xdaforums.com/t/rom-unofficial-11-crown-lineageos-18-1-for-the-amazon-echo-show-8-2019.4766709/)

Der Ablauf im Überblick (Details und Downloads in den Threads):

1. Paket **amonet-crown** herunterladen und auf dem Linux-Rechner entpacken.
2. Den Dienst **ModemManager** stoppen, damit er die USB-Verbindung nicht stört:
   `systemctl stop ModemManager`
3. Das Skript `fastbrick.sh` aus dem amonet-Paket starten und den Echo nach Anleitung anschließen.
4. Danach startet der Echo in die Wiederherstellung **TWRP**.
   Erscheint stattdessen ein Echo mit rotem Dreieck, fastbrick erneut ausführen und den Echo
   mit gedrückter **Lauter-Taste** starten.
5. In TWRP zuerst eine **Sicherung** anlegen, dann *Wipe → Format Data*.
6. LineageOS-ZIP per `adb sideload lineage-18.1-…-crown.zip` installieren
   (*Advanced → ADB Sideload*). Bricht die Übertragung ab, einfach wiederholen.
7. Neu starten und die Ersteinrichtung von LineageOS durchgehen, WLAN verbinden.

---

## 3. Echo Radio installieren

### 3.1 USB-Debugging am Echo einschalten

1. *Einstellungen → Über das Tablet* → 7× auf **Build-Nummer** tippen.
2. *Einstellungen → System → Entwickleroptionen* → **USB-Debugging** einschalten.
3. Echo per USB anschließen und am Computer `adb devices` eingeben.
   Am Echo die Abfrage **„USB-Debugging zulassen“** mit *Immer zulassen* bestätigen.

**Optional ohne Kabel (adb über WLAN):** In den Entwickleroptionen *ADB über Netzwerk* einschalten
(bei LineageOS vorhanden), dann am Computer:

```bash
adb connect <IP-des-Echo>:5555
```

### 3.2 App bekommen

**Variante A – fertige APK (einfach):**
Unter [Releases](https://github.com/Schampango/echo-radio/releases) die neueste `echo-radio-*.apk` herunterladen.

**Variante B – selbst bauen (Linux Mint / Ubuntu, als root):**

```bash
git clone https://github.com/Schampango/echo-radio.git
cd echo-radio
./build.sh
```

Beim ersten Mal installiert das Skript Java 17 und das Android SDK nach `/opt/android-sdk`
(ca. 1 GB Download). Das Ergebnis liegt im Projektordner als `echo-radio-<Version>.apk`
und zusätzlich als `echo-radio.apk`.

### 3.3 Auf den Echo spielen

```bash
adb install -r echo-radio.apk
```

(bei Variante A den Namen der heruntergeladenen Datei einsetzen,
bei Variante B liegt `echo-radio.apk` schon im Projektordner).

---

## 4. Einrichten

Alle Befehle werden am Computer eingegeben, der Echo ist per adb verbunden.

### 4.1 Berechtigungen erteilen

```bash
# Mikrofon für die Sprachsteuerung
adb shell pm grant de.polla.echoradio android.permission.RECORD_AUDIO

# Roter Rand der Sprachsteuerung und Rückkehr zur App auch über anderen Apps
adb shell appops set de.polla.echoradio SYSTEM_ALERT_WINDOW allow
```

Beides geht auch ohne Computer: Beim ersten Start fragt die App nach dem Mikrofon (*Zulassen*),
„Über anderen Apps einblenden“ findest du unter *Einstellungen → Apps → Echo Radio*.

Optional für die **Nachtschaltung** (Display nachts ganz aus): Die App braucht dafür die
Android-Berechtigung „Bildschirm sperren“, sonst nichts.

```bash
adb shell dpm set-active-admin de.polla.echoradio/.ScreenAdmin
```

Alternativ am Echo unter *Bearbeiten → Display → Berechtigung erteilen*.

### 4.2 Als Startbildschirm festlegen

Damit nach dem Einschalten und mit der Home-Geste immer Echo Radio erscheint:

```bash
adb shell cmd package set-home-activity de.polla.echoradio/.MainActivity
```

Oder am Echo: Home-Geste ausführen und bei der Frage nach dem Startbildschirm
**Echo Radio → Immer** wählen.

### 4.3 Sperrbildschirm abschalten (empfohlen)

Die App hält das Display selbst an und dunkelt es ab. Damit beim Aufwecken kein
Sperrbildschirm im Weg ist (nur möglich, wenn keine PIN gesetzt ist):

```bash
adb shell locksettings set-disabled true
```

### 4.4 App starten

```bash
adb shell am start -n de.polla.echoradio/.MainActivity
```

Beim ersten Start lädt die App das deutsche Sprachmodell (ca. 45 MB) von alphacephei.com
und entpackt es. Den Fortschritt zeigt die Weboberfläche oben am 🎤-Knopf. Danach läuft die
Spracherkennung komplett ohne Internet.

### 4.5 Weboberfläche öffnen

Am Echo auf **Bearbeiten** tippen. Unten steht dann „Auch im Browser: http://…:8080“.
Diese Adresse am Computer oder Handy im selben WLAN öffnen. Dort richtest du Knöpfe,
Sprachbefehle, Klang und Display bequem ein. Wie, steht in der
[Bedienungsanleitung](BEDIENUNGSANLEITUNG.md).

---

## 5. Aktualisieren

Die Einstellungen und Knöpfe bleiben beim Aktualisieren erhalten.

```bash
cd echo-radio
git pull
./build.sh
adb install -r echo-radio.apk
adb shell am start -n de.polla.echoradio/.MainActivity
```

Mit einer fertigen APK aus *Releases* reicht `adb install -r <datei>.apk`.

Vor größeren Änderungen lohnt sich eine Sicherung der Knöpfe:
Weboberfläche → **Sicherung herunterladen**.

---

## 6. Deinstallieren

```bash
# nur falls die Nachtschaltung-Berechtigung erteilt wurde, sonst lässt Android nicht deinstallieren
adb shell dpm remove-active-admin de.polla.echoradio/.ScreenAdmin
adb uninstall de.polla.echoradio
```

Danach fragt Android beim nächsten Druck auf Home nach einem anderen Startbildschirm.

---

## 7. Fehlerbehebung

| Problem | Lösung |
|---|---|
| `adb devices` zeigt nichts | Anderes USB-Kabel (Datenkabel), USB-Debugging prüfen, Abfrage am Echo bestätigen |
| `unauthorized` bei adb | Am Echo die Abfrage „USB-Debugging zulassen“ bestätigen |
| Sprachsteuerung reagiert nicht | 🎤 in der Weboberfläche prüfen; Mikrofon-Berechtigung erteilen (4.1); Modell noch nicht geladen? |
| Modell lädt nicht | Internetverbindung prüfen, App neu starten; der Download beginnt dann neu |
| Kein roter Rand über anderen Apps | Berechtigung „Über anderen Apps einblenden“ (4.1), danach 🎤 einmal aus- und einschalten |
| Sender spielt nicht | Stream-Adresse im Browser testen; als Art „Sender suchen“ wählen und den Namen eintragen |
| Weboberfläche nicht erreichbar | Gleiches WLAN? Adresse im Bearbeiten-Modus am Echo ablesen; Port 8080 |
| Display geht trotzdem aus | Die App muss vorne sein; in anderen Apps gilt die normale Bildschirmzeit von Android |

**Protokoll ansehen** (hilft bei jeder Fehlersuche):

```bash
adb logcat -s EchoRadio:*
```

Dort steht u. a., welcher Stream geöffnet wird, welche Titel ankommen und was die
Spracherkennung gehört hat („Gehört: …“).
