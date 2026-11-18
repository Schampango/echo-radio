# Echo Radio

Frei konfigurierbare Senderknöpfe für den Amazon Echo Show 8 (1. Gen, *crown*) mit LineageOS 18.1 (Android 11).

## Bedienung

- **Tippen:** abspielen, nochmal tippen: stoppen. Der laufende Knopf bekommt einen gelben Ring.
- **Bearbeiten** (unten): Knopf antippen öffnet den Editor, **+** legt einen neuen an, **Fertig** beendet.
- **Langer Druck** auf einen Knopf öffnet den Editor jederzeit.
- Kann als Startbildschirm (Home) gewählt werden.

Alle Knöpfe sind gleich groß und sitzen in einem Raster. Die Spaltenzahl wählt die App selbst,
sodass die Knöpfe möglichst groß werden.

## Editor

| Feld | Bedeutung |
|---|---|
| Beschriftung | Text im Kreis, Enter für eine zweite Zeile |
| Art | Radio-Stream · neueste Folge aus Podcast-Feed · Sender suchen (radio-browser.info) · App oder Webseite |
| Ziel | Stream-/Feed-Adresse, Sendername oder Paketname (Liste über „App auswählen“). Bei Apps mehrere Ziele mit `\|` trennen, das erste vorhandene wird gestartet |
| Farbe | 12 Vorgaben oder eigener Farbcode `#RRGGBB` |
| Position | nach vorne / nach hinten |
| Löschen | entfernt den Knopf |

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
