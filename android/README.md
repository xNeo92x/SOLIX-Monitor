# SOLIX Monitor für Android

Native Android-Version des SOLIX Monitor für die **Anker SOLIX Solarbank 4
E5000 Pro**. Die App liest die Live-Daten direkt per Modbus TCP im lokalen
Heimnetz. Sie benötigt weder die Anker-Cloud noch Anker-Zugangsdaten.

## Funktionen

- Live-Werte für PV, Haus, Batterie und Netz
- Akkustand, Lade-/Entladestatus und Betriebsmodus
- Gesamtenergien und technische Gerätedaten
- Leistungsverlauf der letzten 60 Minuten
- Demo-Modus ohne Solarbank
- Verbindungstest und frei wählbares Aktualisierungsintervall
- Material-3-Oberfläche mit automatischem Hell-/Dunkelmodus
- Rein lesender Modbus-Client (`FC03`/`FC04`)

## Voraussetzungen

- Android Studio mit JDK 17
- Android SDK 36
- Android-Gerät ab Android 8.0 (API 26)
- Smartphone und Solarbank im selben lokalen Netzwerk

## Solarbank vorbereiten

1. Anker-App öffnen und die Solarbank auswählen.
2. **Einstellungen → Steuerung durch Drittanbieter → Modbus TCP** aktivieren.
3. Die dort angezeigte lokale IP-Adresse notieren.
4. In SOLIX Monitor Demo-Modus deaktivieren und die IP eintragen.

Standardwerte sind Port `502` und Geräte-ID `1`. Eine DHCP-Reservierung im
Router verhindert, dass sich die IP-Adresse später ändert.

## In Android Studio bauen

1. In Android Studio **Open** wählen.
2. Den Ordner `android` öffnen.
3. Die Gradle-Synchronisierung abwarten.
4. Smartphone per USB-Debugging verbinden und **Run** drücken.

Alternativ im Terminal:

```bash
cd android
./gradlew assembleDebug
```

Die APK liegt anschließend unter:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Installation per ADB:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Datenschutz

Gespeichert werden ausschließlich IP-Adresse, Port, Geräte-ID,
Aktualisierungsintervall und Demo-Einstellung. Die Kommunikation erfolgt direkt
zwischen Smartphone und Solarbank. Es gibt keine Telemetrie und keine
Cloud-Anmeldung.

## Technische Hinweise

Die Registerbelegung entspricht der Desktop-Version und ist für die Solarbank 4
E5000 Pro ausgelegt. Andere SOLIX-Modelle können abweichende Register verwenden.
Der Verlauf wird ausschließlich im Arbeitsspeicher gehalten und beim Beenden der
App verworfen.
