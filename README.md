# Pfand Counter

An Android app that counts German bottle and can deposits (*Pfand*) while you scan your empties.

A barcode does not carry its deposit. Single-use containers are identified by the DPG label, not
by the number. So the app ships a list of barcodes with known deposits, looks up unknown ones
in open product databases, and remembers every answer you give it.

## Features

- Scan barcodes with the camera. The running total is shown with a breakdown by deposit amount.
- A shipped list of barcodes with known deposits; its header names the sources.
- For an unknown barcode, the app looks up the product on Open Food Facts, then on Open Beauty
  Facts and Open Products Facts. Things that are clearly not drinks (sweets, shampoo, batteries)
  are not counted, and neither are syrups. Yogurt or cream in a glass that Open Food Facts marks
  as reusable ("Mehrweg", "Pfand") gets €0.15 suggested; plastic cups and single-use jars do not.
- For drinks it cannot decide, a short guide asks you about the container and learns your
  answer for that barcode.
- QR codes and other non-product codes are ignored.
- If a barcode is unknown but at least three barcodes of the same maker in the list share one
  deposit, that amount is suggested (never counted until you tap it). Cartons, pouches, syrups,
  and wine or spirits in glass get no such suggestion.
- Optional: "Ask Jev" sends the product description (never the barcode) to an AI model, either
  through OpenRouter or directly at TypeSafe, and shows a suggestion with its measured
  reliability. It is off after installation (switch it on under Settings › Jev suggestions), needs your own key for the way you pick, stays off without one, and runs only
  when you tap it.
- Crates: the crate deposit, full crates with their bottles in one tap, and your own crate sizes.
- A statistics page that stays on the phone.
- With TalkBack on, every scan is announced (product, deposit, new total), and the barcode digits
  are left out of the dialogs. Counted, no deposit and unknown vibrate differently; sounds are off
  unless switched on in Settings.
- "Supermarket nearby" opens the phone's map app with a search; the app itself needs no location
  permission.
- Reset and undo reset. No account, no tracking.

## Building

The app is built with Gradle through the wrapper in the repository:

```bash
./gradlew assembleRelease
```

The APK lands in `build-gradle/outputs/apk/release/`. Requirements: JDK 17 or newer and the Android
SDK with `platforms/android-36` and build-tools 37.0.0 (path in `ANDROID_HOME` or in a
`local.properties` line `sdk.dir=…`). The wrapper downloads Gradle 9.8.0 and checks it against the
SHA-256 in `gradle/wrapper/gradle-wrapper.properties`. Every library it fetches (ZXing from Maven
Central; AndroidX AppCompat and Material Components from Google's Maven repository) is pinned by
SHA-256 in `gradle/verification-metadata.xml`, and the build stops if one differs.

Without a key the APK stays unsigned. A local build signs it with `keys/debug.keystore` when that
file exists; the folder is not part of the repository.

An optional local extra source may live in `src-local/` (see `src/app/pfandcounter/ExtraSource.java`).
It is compiled in only on request, with `./build.sh --with-local` (Gradle: `-PwithLocal`); without
the flag it is left out, even when the folder exists.

The APK's application id is `io.github.passport0819.pfandcounter` (the Java package stays
`app.pfandcounter`). To build updates for an installation made under a different id, put that id
on the first line of `app-id.local`.

Tests run on a desktop JVM, see the header comment of each file in `tools/`, for example
`tools/ProductCodeTest.java` and `tools/DecodeTest.java`.

## How the shipped list is made

`tools/seed-from-parquet.sh` extracts the German drinks from Open Food Facts' Parquet export, and
`tools/seed-build.py` turns them into `res/raw/deposits.tsv`: what the product data states outright
("Mehrweg", "Einweg"), rule-based decisions (cans, German glass beer, large plastic bottles,
manufacturer patterns) and the hand-checked entries in `data/geprueft.tsv`, each with its source.
Anything the rules cannot settle stays out of the list, and the app asks instead.

## Privacy

The count stays on the phone. To show product names, the scanned barcode goes to Open Food Facts
(and, if unknown there, Open Beauty Facts and Open Products Facts). "Ask Jev" sends the product
description, never the barcode, to OpenRouter or TypeSafe, whichever way you picked, and only
after you tap it. The keys stay on the device and are excluded from backups. No ads, no
analytics, no account. The full text is in [PRIVACY.md](PRIVACY.md) (English and German).

## Licenses

- Code: GNU General Public License v3.0, see [LICENSE](LICENSE).
- ZXing, AndroidX AppCompat and Material Components for Android (fetched by Gradle, bundled into the
  APK): Apache License 2.0.
- Settings gear icon (`res/drawable/ic_b_settings.xml`): `ic_settings` from Material Design Icons 3.0.1,
  Apache License 2.0.
- Product names and packaging data: © Open Food Facts, Open Beauty Facts and Open Products Facts
  contributors, under the Open Database License (ODbL). Individual contents are under the Database
  Contents License (DbCL).
- The shipped deposit list (`res/raw/deposits.tsv`) is derived from Open Food Facts and is made
  available as a whole under the ODbL 1.0, separately from the code; the list's header says so.
