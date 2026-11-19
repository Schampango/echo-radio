# Echo Radio

Frei konfigurierbare Senderknöpfe für den Amazon Echo Show 8 (1. Gen, *crown*) mit LineageOS 18.1 (Android 11).

## Bedienung

- **Tippen:** abspielen, nochmal tippen: stoppen. Der laufende Knopf bekommt einen gelben Ring.
- **Bearbeiten** (unten): Knopf antippen öffnet den Editor, **+** legt einen neuen an, **Fertig** beendet.
- **Langer Druck** auf einen Knopf öffnet den Editor jederzeit.
- Kann als Startbildschirm (Home) gewählt werden.
- Mehr Knöpfe als auf eine Seite passen (max. 12): Seiten zum Wischen, Punkte unten zeigen die Seite.

### Radio

- Bricht die Verbindung ab (WLAN-Aussetzer, Stream hängt > 20 s), verbindet die App automatisch neu:
  nach 3, 5, 10, 20, dann alle 30 s, höchstens 20 Versuche. Kommt das WLAN zurück, sofort.
- Schickt der Sender Titel und Interpret mit (ICY-Metadaten), stehen sie in der Statuszeile.
- Abgespielt wird mit ExoPlayer (Media3); der läuft auch mit reinen AAC-Streams wie Brillux Radio.
- Fehlersuche: `adb logcat -s EchoRadio:*`

### Podcasts (Art „Neueste Folge aus Podcast-Feed“)

- Beim Stoppen merkt sich die App Folge und Position (zusätzlich alle 5 Sekunden).
- Beim nächsten Tipp geht es dort weiter, solange im Feed keine neuere Folge erschienen ist.
  Eine neue Folge oder eine zu Ende gehörte beginnt von vorne.
- Unten erscheint ein Fortschrittsbalken; ziehen zum Spulen.

Alle Knöpfe sind gleich groß und sitzen in einem Raster. Die Spaltenzahl wählt die App selbst,
sodass die Knöpfe möglichst groß werden.

## Sprachsteuerung

Läuft komplett auf dem Echo (Vosk, kleines deutsches Modell), ohne Internet und ohne Home Assistant.
Das Modell (ca. 45 MB) lädt die App beim ersten Start einmal herunter.

- **„Alexa, …“** – nach „Alexa“ piept es kurz, die Musik wird leiser und ein **roter Rand**
  pulsiert um das Display; danach kurz **grün** (verstanden) oder **orange** (nicht verstanden)
- Rand auch über anderen Apps (Apple Music, YouTube):
  `adb shell appops set de.polla.echoradio SYSTEM_ALERT_WINDOW allow`, dann 🎤 einmal aus- und einschalten
- „Alexa, spiel DLF“ / „Alexa, Eins Live“ / „Alexa, Nachrichten“ – Name eines Knopfs
- „Alexa, Stopp“, „Alexa, lauter“, „Alexa, leiser“, „Alexa, Lautstärke 5“
- Auch mit Pause: „Alexa“ … *piep* … „Deutschlandfunk“
- Weitere Namen pro Knopf in der Weboberfläche unter **Sprachbefehl**
- Ein/Aus: 🎤-Knopf oben in der Weboberfläche oder die Mikrofon-Taste am Echo
- Mikrofon-Berechtigung fehlt? `adb shell pm grant de.polla.echoradio android.permission.RECORD_AUDIO`
- Protokoll: `adb logcat -s EchoRadio:*` (zeigt „Gehört: …“)

## Display

- Solange die Radio-App vorne ist, geht das Display nicht aus, sondern wird nach einer
  einstellbaren Zeit abgedunkelt (Standard: 60 s, Helligkeit 5 %, 0 % = schwarz).
- Antippen weckt auf (die erste Berührung startet keinen Sender), „Alexa“ weckt für den
  Sprachbefehl auf und dunkelt danach nach etwa 8 Sekunden wieder ab.
- Optional holt sich die App nach X Minuten aus anderen Apps wieder nach vorne
  (zählt ab dem Wechsel in die andere App; braucht „Über anderen Apps einblenden“, s. o.).
- Einstellen: am Echo unter „Bearbeiten“ → „Display“ oder in der Weboberfläche über „🖥 Display“.

## Weboberfläche

Die App startet auf dem Echo einen kleinen Webserver: **http://&lt;IP des Echo&gt;:8080**
(die Adresse steht im Bearbeiten-Modus unten in der Statuszeile).

- alle Knöpfe als Vorschau, anklicken zum Bearbeiten, ziehen zum Sortieren
- Beschriftung, Art, Ziel, App-Auswahl, Farbe, Logo samt Logo-Suche
- neue Knöpfe, löschen, Sicherung herunterladen/laden
- Fernbedienung: ▶ abspielen, Stopp, Lautstärke, „läuft gerade“
- Änderungen gehen sofort auf den Echo; wurde dort gleichzeitig etwas geändert,
  meldet die Seite das, statt es zu überschreiben

Ohne Passwort – nur für das Heimnetz gedacht.

## Editor

| Feld | Bedeutung |
|---|---|
| Beschriftung | Text im Kreis, Enter für eine zweite Zeile |
| Art | Radio-Stream · neueste Folge aus Podcast-Feed · Sender suchen (radio-browser.info) · App oder Webseite |
| Ziel | Stream-/Feed-Adresse, Sendername oder Paketname (Liste über „App auswählen“). Bei Apps mehrere Ziele mit `\|` trennen, das erste vorhandene wird gestartet |
| Farbe | 12 Vorgaben oder eigener Farbcode `#RRGGBB` |
| Logo | Bildadresse (PNG/JPG), „Logo suchen“ bei radio-browser.info, „Vorschau“, „Entfernen“. SVG geht nicht |
| Position | nach vorne / nach hinten |
| Löschen | entfernt den Knopf |

## Sichern und Wiederherstellen

Im Bearbeiten-Modus: **Sichern** speichert alle Knöpfe als `echo-radio-knoepfe.json`
(z. B. in *Downloads*), **Laden** liest eine solche Datei wieder ein und ersetzt die aktuellen Knöpfe.
Vom PC aus: `adb pull /sdcard/Download/echo-radio-knoepfe.json`

## Standardknöpfe

| Knopf | Quelle |
|---|---|
| News | neueste Sendung aus `deutschlandfunk.de/nachrichten-108.xml` |
| 1 Live | WDR 1LIVE Stream |
| DLF | Deutschlandfunk Stream |
| Brillux | Suche „Brillux Radio“ bei radio-browser.info |
| Apple Musik | Apple-Music-App |
| Youtube | NewPipe, sonst YouTube-App, sonst Browser |
| Bundesliga Konferenz | WDR 2 Stream (Platzhalter) |

## Bauen (Linux Mint / Ubuntu, als root)

```bash
./build.sh
```

Installiert beim ersten Mal Java 17 und das Android SDK nach `/opt/android-sdk`.

## Installieren

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
