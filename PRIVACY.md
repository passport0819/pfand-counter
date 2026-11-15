# Privacy policy / Datenschutzhinweise

[English](#english) · [Deutsch](#deutsch)

The same text, in short cards, is in the app under Settings › About and data sources › Privacy.

## English

**In short:** Pfand Counter has no account, no ads, no tracking and no server of its own. Your
count stays on your phone. Only the scanned barcode is sent to the open product database Open Food
Facts, so the app can show the product name.

### Who is responsible?

The app is published by a private individual as free software (GPL 3). The developer runs no
server and receives no data from the app. Contact:

- E-mail: pfand-counter@tuta.com
- GitHub: https://github.com/passport0819/pfand-counter/issues

### Camera

The app uses the camera to read barcodes. The images are processed only in the phone's memory. No
photo is saved and no image is sent anywhere.

### Open Food Facts (product names)

When you scan a barcode, the app asks the open database Open Food Facts about the product. It sends:

- the barcode number,
- an app name ("PfandCounter/1.0" with a link to the source code),
- your IP address, which every internet request unavoidably carries.

If Open Food Facts does not know the product, the app asks its sister databases Open Beauty Facts
and Open Products Facts with the same data. All three are run by the non-profit Open Food Facts
association (France); one privacy policy covers all three: https://world.openfoodfacts.org/privacy

This happens once per barcode each time the app is started, also for barcodes the app already
knows, to show their name. Without internet the app still counts, but shows no product names.

### Only when you tap

- **Share** passes your count as text to the app you pick (messenger, notes, e-mail).
- **Supermarket nearby** hands the word "supermarket" to your map app. Pfand Counter itself has no
  location permission and never learns where you are; what the map app does with your location is
  up to that app.
- **Report a translation mistake** opens a GitHub page in your browser. Nothing is sent until you
  send it there yourself.

### What is stored on your phone

Only in the app's private storage, which other apps cannot read:

- the current count (barcodes, product names, deposits) and one state for undo,
- barcodes whose deposit you taught the app,
- the crate deposit you chose and the crate sizes you saved,
- settings (design, sort order, whether notices were already shown); the language is kept by
  Android itself,
- the statistics page: how many bottles and crates were counted, per deposit amount and per month,
  how many were scanned or added by hand.

This data may be included in your phone's backup if you turned backups on.

Everything is deleted when you uninstall the app or clear its data in Android's settings.

### What the app does not do

- no ads, no analytics, no crash reporting; the statistics page is only for you and never leaves
  the phone,
- no account, no sign-in,
- no location, no contacts, no access to photos or files.

### Permissions

- **Camera:** read barcodes.
- **Internet:** ask Open Food Facts and its sister databases about scanned products.
- **Vibrate:** a short buzz as feedback when scanning.

### Your rights

Since the developer receives no data from you, there is nothing the developer could disclose or
delete. You delete the data on your phone yourself (see above). For data held by Open Food Facts,
contact them. You also have the right to complain to a data protection
authority.

### Changes

If what the app sends ever changes, this text will be updated first. Earlier versions can be seen
in the history of this repository.

## Deutsch

**Kurz gesagt:** Pfand Counter hat kein Konto, keine Werbung, keine Überwachung und keinen eigenen
Server. Deine Zählung bleibt auf deinem Handy. Nur der gescannte Barcode geht an die freie
Produktdatenbank Open Food Facts, damit die App den Produktnamen anzeigen kann.

### Wer ist verantwortlich?

Die App wird von einer Privatperson als freie Software (GPL 3) veröffentlicht. Der Entwickler
betreibt keinen Server und erhält von der App keine Daten. Kontakt:

- E-Mail: pfand-counter@tuta.com
- GitHub: https://github.com/passport0819/pfand-counter/issues

### Kamera

Die App braucht die Kamera, um Barcodes zu lesen. Die Bilder werden nur im Arbeitsspeicher des
Handys ausgewertet. Es wird kein Foto gespeichert und kein Bild verschickt.

### Open Food Facts (Produktnamen)

Wenn du einen Barcode scannst, fragt die App die offene Datenbank Open Food Facts nach dem Produkt.
Übertragen werden:

- die Barcode-Nummer,
- ein App-Name („PfandCounter/1.0“ mit Link zum Quellcode),
- technisch unvermeidbar deine IP-Adresse (wie bei jedem Internetzugriff).

Findet Open Food Facts das Produkt nicht, fragt die App mit denselben Angaben die
Schwesterdatenbanken Open Beauty Facts und Open Products Facts. Alle drei werden vom gemeinnützigen
Verein Open Food Facts (Frankreich) betrieben; für alle drei gilt dieselbe Datenschutzerklärung:
https://world.openfoodfacts.org/privacy

Das geschieht einmal je Barcode und App-Start, auch bei Barcodes, die die App schon kennt, um ihren
Namen zu zeigen. Ohne Internet zählt die App trotzdem, zeigt dann aber keine Produktnamen.

### Nur wenn du tippst

- **Teilen** gibt deine Zählung als Text an die App, die du auswählst (Messenger, Notizen, E-Mail).
- **Supermarkt in der Nähe** gibt das Wort „Supermarkt“ an deine Karten-App. Pfand Counter selbst
  hat keine Standort-Berechtigung und erfährt nie, wo du bist; was die Karten-App mit deinem
  Standort macht, entscheidet diese App.
- **Übersetzungsfehler melden** öffnet eine GitHub-Seite in deinem Browser. Verschickt wird erst,
  wenn du dort selbst absendest.

### Was auf dem Handy gespeichert wird

Nur im privaten Speicher der App, für andere Apps nicht lesbar:

- die aktuelle Zählung (Barcodes, Produktnamen, Pfandbeträge) und ein Stand zum Rückgängigmachen,
- Barcodes, deren Pfand du der App beigebracht hast,
- das gewählte Kastenpfand und gespeicherte Kastengrößen,
- Einstellungen (Design, Sortierung, ob Hinweise schon gezeigt wurden); die Sprache verwaltet
  Android selbst,
- die Statistik-Seite: wie viele Flaschen und Kästen gezählt wurden, je Pfandbetrag und Monat,
  wie viele gescannt und wie viele von Hand.

Diese Daten kann die Sicherungsfunktion deines Handys mitsichern, wenn du sie eingeschaltet hast.

Alles wird gelöscht, wenn du die App deinstallierst oder in den Android-Einstellungen ihre Daten
löschst.

### Was die App nicht tut

- keine Werbung, keine Analyse- oder Absturzdienste; die Statistik-Seite ist nur für dich und
  verlässt das Handy nie,
- kein Konto, keine Anmeldung,
- kein Standort, keine Kontakte, kein Zugriff auf Fotos oder Dateien.

### Berechtigungen

- **Kamera:** Barcodes lesen.
- **Internet:** Open Food Facts und seine Schwesterdatenbanken nach gescannten Produkten fragen.
- **Vibration:** kurzes Rütteln als Rückmeldung beim Scannen.

### Deine Rechte

Da der Entwickler keine Daten von dir erhält, kann er dazu keine Auskunft geben oder etwas löschen.
Deine Daten auf dem Handy löschst du selbst (siehe oben). Für Daten bei Open Food Facts wende dich
an den Verein. Du hast außerdem das Recht, dich bei einer
Datenschutz-Aufsichtsbehörde zu beschweren.

### Änderungen

Ändert sich, welche Daten die App verschickt, wird dieser Text vorher angepasst. Frühere Fassungen
sind im Verlauf dieses Repositorys einsehbar.
