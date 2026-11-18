# Echo Radio

Senderknöpfe für den Amazon Echo Show 8 (1. Gen, *crown*) mit LineageOS 18.1 (Android 11).

| Knopf | Quelle |
|---|---|
| News | neueste Sendung aus dem Feed `deutschlandfunk.de/nachrichten-108.xml` |
| 1 Live | WDR 1LIVE Stream |
| DLF | Deutschlandfunk Stream |
| Brillux | Suche „Brillux Radio“ bei radio-browser.info |
| Apple Musik | startet die Apple-Music-App |
| Youtube | startet NewPipe, sonst YouTube-App, sonst Browser |
| Bundesliga Konferenz | WDR 2 Stream (Platzhalter) |

- Tippen: abspielen, nochmal tippen: stoppen
- Lange drücken: Stream-Adresse ändern („Standard“ setzt zurück)
- Kann als Startbildschirm (Home) gewählt werden

## Bauen (Linux Mint / Ubuntu, als root)

```bash
./build.sh
```

Installiert beim ersten Mal Java 17 und das Android SDK nach `/opt/android-sdk`.

## Installieren

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
