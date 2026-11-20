# Bedienungsanleitung

Echo Radio macht aus dem Echo Show 8 ein Internetradio mit großen Senderknöpfen,
Sprachsteuerung, Uhr und Weboberfläche. Die Installation steht in der
[Installationsanleitung](INSTALLATION.md).

**Inhalt**

1. [Der Startbildschirm](#1-der-startbildschirm)
2. [Abspielen](#2-abspielen)
3. [Sprachsteuerung](#3-sprachsteuerung)
4. [Display, Uhr und Abdunkeln](#4-display-uhr-und-abdunkeln)
5. [Knöpfe bearbeiten am Echo](#5-knöpfe-bearbeiten-am-echo)
6. [Klang (Equalizer)](#6-klang-equalizer)
7. [Die Weboberfläche](#7-die-weboberfläche)
8. [Sichern und Wiederherstellen](#8-sichern-und-wiederherstellen)
9. [Tipps und häufige Fragen](#9-tipps-und-häufige-fragen)

---

## 1. Der Startbildschirm

- **Runde Knöpfe** mit Beschriftung oder Senderlogo. Alle sind gleich groß und sitzen in einem Raster.
- **Statuszeile unten:** Was gerade läuft, z. B. „1 Live – Interpret - Titel“, oder „Bereit“.
- **Stopp** (rot, unten rechts) beendet die Wiedergabe.
- **Bearbeiten** (unten) schaltet in den Bearbeiten-Modus.
- **Mehrere Seiten:** Auf eine Seite passen bis zu 12 Knöpfe. Gibt es mehr, wischst du nach links
  oder rechts. Punkte unten zeigen, auf welcher Seite du bist.
- Die **Lautstärketasten** am Echo regeln die Musik.

---

## 2. Abspielen

| Aktion | Ergebnis |
|---|---|
| Knopf antippen | Spielt ab. Der laufende Knopf bekommt einen gelben Ring. |
| Laufenden Knopf erneut antippen | Stoppt. |
| Anderen Knopf antippen | Wechselt sanft (ohne Knacken) zum neuen Sender. |
| **Stopp** | Beendet die Wiedergabe. |

Je nach **Art** des Knopfs passiert Folgendes:

- **Radio-Stream:** spielt den Sender. Schickt der Sender Interpret und Titel mit,
  stehen sie in der Statuszeile.
- **Neueste Folge aus Podcast-Feed:** spielt die neueste Folge, z. B. die aktuellen Nachrichten.
  - Beim Stoppen merkt sich die App die Stelle. Beim nächsten Tippen geht es dort weiter,
    solange keine neuere Folge erschienen ist.
  - Unten erscheint ein **Fortschrittsbalken**. Ziehen zum Spulen.
- **Sender suchen:** sucht den Sender per Name bei radio-browser.info und spielt ihn.
  Praktisch, wenn du die Stream-Adresse nicht kennst.
- **App oder Webseite öffnen:** startet eine App (z. B. Apple Music, NewPipe) oder öffnet eine Webseite.

**Verbindungsabbrüche** repariert die App selbst. Bei einem WLAN-Aussetzer oder hängendem Stream
verbindet sie neu: nach 3, 5, 10, 20 Sekunden, dann alle 30 Sekunden, höchstens 20-mal.
Kommt das WLAN zurück, sofort.

---

## 3. Sprachsteuerung

Die Spracherkennung läuft **vollständig auf dem Echo**, ohne Internet und ohne Cloud.

### So funktioniert es

1. Sag das **Aktivierungswort**, ab Werk **„Alexa“**.
2. Es **piept**, die Musik wird leiser und ein **roter Rand** pulsiert um das Display.
3. Sag den **Befehl**. Das geht in einem Satz („Alexa, spiel DLF“) oder mit Pause
   („Alexa“ … *piep* … „DLF“). Du hast etwa 7 Sekunden Zeit.
4. Der Rand blinkt kurz **grün**, wenn der Befehl verstanden wurde, oder **orange**, wenn nicht.

### Befehle

| Du sagst | Ergebnis |
|---|---|
| „Alexa, spiel Deutschlandfunk“ | spielt den Knopf mit diesem Namen |
| „Alexa, Eins Live“ | „spiel“ kann man weglassen |
| „Alexa, Nachrichten“ | spielt den Nachrichten-Knopf |
| „Alexa, Stopp“ | Wiedergabe beenden |
| „Alexa, lauter“ / „Alexa, leiser“ | eine Stufe lauter oder leiser (10 %) |
| „Alexa, Lautstärke 5“ | Lautstärke auf 50 % (1 bis 10) |

Die Erkennung ist bewusst tolerant. „Brille“ findet z. B. auch „Brillux“, „Bundesliga“ findet
„Bundesliga Konferenz“.

### Wörter anpassen

- **Namen eines Senders:** in der Weboberfläche beim Knopf unter **Sprachbefehl**.
  Mehrere Namen trennst du mit Komma, z. B. „Deutschlandfunk, d l f“.
- **Alle anderen Wörter:** in der Weboberfläche unter **🗣 Wörter** (siehe [7.5](#75--wörter)).
  Das betrifft Aktivierungswort, Stopp, lauter, leiser, Lautstärke und Füllwörter.

### Ein- und ausschalten

Mit dem **🎤-Knopf** oben in der Weboberfläche. Ist die Sprachsteuerung aus, ist das Mikrofon
für die App vollständig geschlossen.

> **Hinweis:** Das kleine Sprachmodell kennt normale deutsche Wörter. Ausgedachte Wörter,
> englische Namen oder Abkürzungen erkennt es oft anders. Ein Blick auf „Zuletzt gehört“ in der
> Weboberfläche zeigt, was es verstanden hat. Genau das trägst du dann als Sprachbefehl ein.

---

## 4. Display, Uhr und Abdunkeln

- Solange Echo Radio im Vordergrund ist, **geht das Display nicht aus**.
- Nach einer einstellbaren Zeit ohne Berührung wird es **abgedunkelt**, ab Werk nach 60 Sekunden.
  Dann erscheint eine **Uhr** mit Datum und darunter, was gerade läuft: Sender, Interpret und Titel.
- **Aufwecken:**
  - **Antippen.** Die erste Berührung weckt nur auf und startet keinen Sender aus Versehen.
  - **„Alexa“.** Das Display wird hell, solange zugehört wird, und dunkelt etwa
    8 Sekunden nach dem Befehl wieder ab.
- **Zurück zur Radio-App** (ab Werk aus): Hast du eine andere App geöffnet, holt sich Echo Radio
  nach der eingestellten Zeit wieder nach vorne. Die Zeit zählt ab dem Wechsel in die andere App.

- **Nachtschaltung** (ab Werk aus): In der eingestellten Zeit, z. B. 02:00 bis 08:00, geht das
  Display **ganz aus**. Radio und Sprachsteuerung laufen weiter. Mit der Taste oben am Echo lässt es sich
  nachts kurz einschalten; nach der Abdunkel-Zeit (mindestens 1 Minute) geht es wieder aus.
  Morgens schaltet es sich von selbst ein. Dafür braucht die App einmalig die Berechtigung
  „Bildschirm sperren“ (siehe [Installationsanleitung](INSTALLATION.md#41-berechtigungen-erteilen));
  ohne sie wird das Display nachts nur schwarz.

**Einstellen:** am Echo unter *Bearbeiten → Display* oder in der Weboberfläche unter **🖥 Display**.

| Einstellung | Bedeutung |
|---|---|
| Abdunkeln nach | nie, 15 s … 30 min |
| Helligkeit abgedunkelt | 0–30 %; ohne Uhr bedeutet 0 % ganz schwarz |
| Uhr und laufenden Titel zeigen | an/aus |
| Aus anderen Apps zurück nach | nie, 1 … 60 min |
| Display nachts ganz ausschalten | an/aus, mit Uhrzeit von … bis … (auch über Mitternacht, z. B. 23:00–06:00) |

---

## 5. Knöpfe bearbeiten am Echo

Tippe unten auf **Bearbeiten**. Unten erscheinen dann zusätzliche Knöpfe:

| Knopf | Funktion |
|---|---|
| **Sichern** | alle Knöpfe als Datei speichern |
| **Laden** | gesicherte Knöpfe wieder einlesen |
| **Display** | Abdunkeln, Uhr, Rückkehr zur App |
| **Klang** | Equalizer |
| **Fertig** | Bearbeiten beenden |

Im Bearbeiten-Modus öffnet **Antippen** eines Knopfs den Editor, **+** legt einen neuen an.
Ein **langer Druck** öffnet den Editor auch außerhalb des Bearbeiten-Modus.

### Der Editor

| Feld | Bedeutung |
|---|---|
| Beschriftung | Text im Kreis, Enter für eine zweite Zeile |
| Art | Radio-Stream · Neueste Folge aus Podcast-Feed · Sender suchen · App oder Webseite öffnen |
| Ziel | Stream-Adresse, Feed-Adresse, Sendername oder App. Bei Apps hilft **App auswählen**. Mehrere Apps mit `\|` trennen, die erste vorhandene wird gestartet. |
| Farbe | 12 Vorgaben oder eigener Farbcode `#RRGGBB` |
| Logo | Bildadresse (PNG/JPG, kein SVG). **Logo suchen** findet Senderlogos bei radio-browser.info, **Vorschau** zeigt es an. |
| Position | **◀ nach vorne** / **nach hinten ▶** |
| Löschen | entfernt den Knopf (mit Rückfrage) |

Bequemer geht das Bearbeiten in der Weboberfläche.

---

## 6. Klang (Equalizer)

Am Echo unter *Bearbeiten → Klang* oder in der Weboberfläche unter **🎚 Klang**:

- **Equalizer an/aus**
- Ein **Regler pro Frequenzband**. Beim Echo sind das meist 5 Bänder (60 Hz bis 14 kHz) mit ±15 dB.
- **Voreinstellungen** des Geräts, z. B. Rock, Pop, Jazz. Danach kannst du weiter anpassen.
- **Alles auf 0** setzt den Klang neutral.

Änderungen sind sofort zu hören, wenn ein Sender läuft. Am Echo übernimmt **Speichern** sie und
**Abbrechen** stellt den alten Klang wieder her. In der Weboberfläche wird sofort gespeichert.
Der Equalizer gilt für alle Sender und Podcasts von Echo Radio, nicht für andere Apps.

---

## 7. Die Weboberfläche

Öffne im Browser **http://&lt;IP des Echo&gt;:8080**. Die Adresse steht am Echo im
Bearbeiten-Modus unten in der Statuszeile. Computer oder Handy müssen im selben WLAN sein.

> Die Weboberfläche hat **kein Passwort** und ist nur für das eigene Heimnetz gedacht.
> Gib Port 8080 nicht ins Internet frei.

### 7.1 Kopfleiste

- **Läuft gerade:** Sender und Titel, grüner Punkt bei Wiedergabe
- **🎤** Sprachsteuerung an/aus, zeigt auch den Ladefortschritt des Sprachmodells
- **🗣 Wörter**, **🎚 Klang**, **🖥 Display** öffnen die jeweiligen Bereiche
- **Lautstärkeregler** und **Stopp**

### 7.2 Knöpfe

- Alle Knöpfe als Vorschau. **Anklicken** öffnet rechts den Editor.
- **Ziehen** sortiert die Knöpfe um.
- **▶** am Knopf spielt ihn auf dem Echo ab.
- **+ Neuer Knopf** legt einen an.

### 7.3 Editor

Die Felder sind dieselben wie am Echo (siehe [5](#der-editor)). Dazu kommen:

- **Sprachbefehl:** weitere Namen, auf die die Sprachsteuerung hört, mit Komma getrennt.
- **Logo-Suche** mit eigenem Suchfeld. Findet die Suche nichts, kannst du die Bildadresse eines
  Logos von der Webseite des Senders kopieren (Rechtsklick → *Bildadresse kopieren*).
- **Speichern:** Änderungen werden ohnehin nach kurzer Pause automatisch übernommen.
  Oben steht „✓ Gespeichert“.

Wurde gleichzeitig am Echo etwas geändert, erscheint ein Hinweis mit **Neu laden**, statt
die Änderung am Echo zu überschreiben.

### 7.4 🖥 Display

Dieselben Einstellungen wie in [Abschnitt 4](#4-display-uhr-und-abdunkeln). Sie werden sofort gespeichert.

### 7.5 🗣 Wörter

Hier legst du fest, auf welche Wörter die Sprachsteuerung hört:

| Gruppe | Bedeutung | Ab Werk |
|---|---|---|
| Aktivierungswort | weckt die Sprachsteuerung | alexa |
| Stopp | Wiedergabe beenden | stopp, stop, aus, pause, ruhe, halt, … |
| Lauter / Leiser | eine Stufe lauter/leiser | lauter / leiser |
| Lautstärke | gefolgt von 1–10 | lautstärke |
| Füllwörter | werden am Anfang überhört | spiel, bitte, mach, den, die, das, … |

- **Anklicken** eines Worts: ändern, mit Enter bestätigen
- **×**: löschen. In jeder Gruppe außer den Füllwörtern muss mindestens ein Wort bleiben.
- **+ hinzufügen**: neues Wort eintippen, Enter. Begriffe aus mehreren Wörtern gehen auch, z. B. „sei still“.
- **Mehrere Aktivierungswörter** sind möglich, z. B. „alexa“ und „computer“.
- **Zuletzt gehört** zeigt, was der Echo verstanden hat und was er daraus gemacht hat.
- **Alles auf Standard zurücksetzen** stellt den Auslieferungszustand wieder her.

**Tipp zum Aktivierungswort:** Nimm ein längeres Wort, das im Alltag und im Radio selten vorkommt.
Sonst wacht der Echo ständig auf.

### 7.6 🎚 Klang

Siehe [Abschnitt 6](#6-klang-equalizer).

---

## 8. Sichern und Wiederherstellen

Gesichert werden alle Knöpfe mit Namen, Zielen, Farben, Logos und Sprachbefehlen.

- **Weboberfläche:** **Sicherung herunterladen** speichert `echo-radio-knoepfe.json` auf dem Computer,
  **Sicherung laden** liest sie wieder ein.
- **Am Echo:** *Bearbeiten → Sichern* speichert die Datei z. B. in *Downloads*, *Laden* liest sie ein.
  Vom Computer holen: `adb pull /sdcard/Download/echo-radio-knoepfe.json`

Beim Laden werden die aktuellen Knöpfe ersetzt (mit Rückfrage). Die Datei ist lesbares JSON
und lässt sich auch auf einen zweiten Echo übertragen.

---

## 9. Tipps und häufige Fragen

**Wo finde ich Stream-Adressen?**
Auf [radio-browser.info](https://www.radio-browser.info) nach dem Sender suchen und die Stream-URL kopieren.
Oder als Art einfach **Sender suchen** wählen und nur den Namen eintragen.

**Ein Sender zeigt keine Titel an.**
Nicht jeder Sender schickt Titelinformationen mit. Manche senden zwischen den Songs nur ihren
eigenen Namen, dann steht dort „läuft“.

**Die Sprachsteuerung versteht einen Sender nicht.**
Sag den Namen und schau in der Weboberfläche unter **🗣 Wörter** auf „Zuletzt gehört“.
Das gehörte Wort trägst du beim Knopf unter **Sprachbefehl** ein.

**Reagiert der Echo auch, wenn im Radio „Alexa“ gesagt wird?**
Ja, das kann passieren. Wähle dann ein anderes Aktivierungswort.

**Hört der Echo ständig mit?**
Das Mikrofon ist offen, solange die Sprachsteuerung an ist. Ausgewertet wird aber nur auf
dem Gerät, nichts verlässt den Echo. Mit 🎤 schaltest du sie ab.

**Kann ich Apple Music, YouTube usw. benutzen?**
Ja, über einen Knopf der Art **App oder Webseite öffnen**. Mit „Zurück zur Radio-App“ kommt
Echo Radio danach von selbst wieder nach vorne.

**Wie bekomme ich die Amazon-Software zurück?**
Über die in TWRP angelegte Sicherung. Siehe den XDA-Thread in der [Installationsanleitung](INSTALLATION.md).
