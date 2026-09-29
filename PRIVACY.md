# Privacy policy / Datenschutzhinweise

[English](#english) · [Deutsch](#deutsch)

The same text, in short cards, is in the app under Settings › About and data sources › Privacy.

## English

**In short:** Pfand Counter has no account, no ads, no tracking and no server of its own. Your
count stays on your phone. Only the scanned barcode is sent to the open product database Open Food
Facts, so the app can show the product name. An AI service is asked only if you switched it on,
entered a key yourself and tap the button. It is off when the app is installed.

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

### Jev, the AI suggestion (optional, off by default, only with your own key)

The app can ask an AI service to suggest a deposit. Jev is off after installation: there is no
"Ask Jev" button and no key field until you switch it on under Settings › Jev suggestions. Even then
this only happens if

1. you entered an access key for one of the two providers (OpenRouter or TypeSafe) in the app
   yourself, and
2. you tap "Ask Jev" for an unknown product.

The app then sends two requests to the chosen provider. They contain only a product description
taken from Open Food Facts: brand and product name (shortened), size, packaging details and up to
three product categories. **The barcode is not sent.** The requests also carry your key (so the
provider can bill you) and your IP address. The provider bills your account there; its privacy
policy applies:

- OpenRouter: https://openrouter.ai/privacy
- TypeSafe: https://typesafe.ai/legal/privacy-policy

Switched off or without a key, the app never contacts an AI service.

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
  how many were scanned or added by hand,
- if entered: your AI key, the chosen provider, and a counter of how many questions you asked and
  roughly what they cost.

The AI key, the provider choice and the counter are kept in an area excluded from Android backups.
The other data may be included in your phone's backup if you turned backups on.

Everything is deleted when you uninstall the app or clear its data in Android's settings.

### What the app does not do

- no ads, no analytics, no crash reporting; the statistics page is only for you and never leaves
  the phone,
- no account, no sign-in,
- no location, no contacts, no access to photos or files.

### Permissions

- **Camera:** read barcodes.
- **Internet:** ask Open Food Facts and, if set up, the AI service.
- **Vibrate:** a short buzz as feedback when scanning.

### Your rights

Since the developer receives no data from you, there is nothing the developer could disclose or
delete. You delete the data on your phone yourself (see above). For data held by Open Food Facts
or the AI provider, contact them. You also have the right to complain to a data protection
authority.

### Changes

If what the app sends ever changes, this text will be updated first. Earlier versions can be seen
in the history of this repository.

## Deutsch

**Kurz gesagt:** Pfand Counter hat kein Konto, keine Werbung, keine Überwachung und keinen eigenen
Server. Deine Zählung bleibt auf deinem Handy. Nur der gescannte Barcode geht an die freie
Produktdatenbank Open Food Facts, damit die App den Produktnamen anzeigen kann. Ein KI-Dienst wird
nur gefragt, wenn du ihn eingeschaltet, selbst einen Schlüssel eingetragen hast und auf den Knopf
tippst. Nach der Installation ist er aus.

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

### Jev, der KI-Vorschlag (freiwillig, anfangs aus, nur mit eigenem Schlüssel)

Die App kann auf Wunsch einen KI-Dienst um einen Pfand-Vorschlag bitten. Nach der Installation ist
Jev aus: Es gibt keinen Knopf „Jev fragen“ und kein Schlüsselfeld, bis du Jev unter Einstellungen ›
Jev-Vorschläge einschaltest. Auch dann passiert es nur, wenn

1. du selbst in der App einen Zugangsschlüssel für einen der beiden Anbieter eingetragen hast
   (OpenRouter oder TypeSafe), und
2. du bei einem unbekannten Produkt auf „Jev fragen“ tippst.

Dann werden zwei Anfragen an den gewählten Anbieter geschickt. Sie enthalten nur eine
Produktbeschreibung aus Open Food Facts: Marke und Produktname (gekürzt), Füllmenge, Angaben zur
Verpackung und bis zu drei Produktkategorien. **Der Barcode wird nicht mitgeschickt.** Dazu kommen
dein Schlüssel (damit der Anbieter dich abrechnen kann) und deine IP-Adresse. Die Kosten rechnet
der Anbieter über dein Konto dort ab; es gilt dessen Datenschutzerklärung:

- OpenRouter: https://openrouter.ai/privacy
- TypeSafe: https://typesafe.ai/legal/privacy-policy

Ausgeschaltet oder ohne Schlüssel schickt die App nie etwas an einen KI-Dienst.

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
  wie viele gescannt und wie viele von Hand,
- falls eingetragen: dein KI-Schlüssel, die Wahl des Anbieters und ein Zähler, wie viele Fragen du
  gestellt hast und was sie ungefähr gekostet haben.

KI-Schlüssel, Anbieterwahl und Zähler liegen in einem Bereich, der von Android-Sicherungen
ausgenommen ist. Die übrigen Daten kann die Sicherungsfunktion deines Handys mitsichern, wenn du sie
eingeschaltet hast.

Alles wird gelöscht, wenn du die App deinstallierst oder in den Android-Einstellungen ihre Daten
löschst.

### Was die App nicht tut

- keine Werbung, keine Analyse- oder Absturzdienste; die Statistik-Seite ist nur für dich und
  verlässt das Handy nie,
- kein Konto, keine Anmeldung,
- kein Standort, keine Kontakte, kein Zugriff auf Fotos oder Dateien.

### Berechtigungen

- **Kamera:** Barcodes lesen.
- **Internet:** Open Food Facts und, falls eingerichtet, den KI-Dienst fragen.
- **Vibration:** kurzes Rütteln als Rückmeldung beim Scannen.

### Deine Rechte

Da der Entwickler keine Daten von dir erhält, kann er dazu keine Auskunft geben oder etwas löschen.
Deine Daten auf dem Handy löschst du selbst (siehe oben). Für Daten bei Open Food Facts oder beim
KI-Anbieter wende dich an diese. Du hast außerdem das Recht, dich bei einer
Datenschutz-Aufsichtsbehörde zu beschweren.

### Änderungen

Ändert sich, welche Daten die App verschickt, wird dieser Text vorher angepasst. Frühere Fassungen
sind im Verlauf dieses Repositorys einsehbar.
