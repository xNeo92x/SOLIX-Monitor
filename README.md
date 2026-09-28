# SOLIX Monitor

HINWEIS: Zu 100% mit ChatGPT erstellt und lokal unter CachyOS, Nobara, Windows und Android 16 getestet.

Eine schlanke, lokale Desktop-App für die **Anker SOLIX Solarbank 4 E5000 Pro**.
Sie läuft unter Windows und Linux, liest die Live-Daten direkt per Modbus TCP
aus dem Heimnetz und benötigt weder Home Assistant noch einen Cloud-Login.

Eine native Android-Version befindet sich im Ordner [`android`](android/README.md).
Sie verwendet dieselbe lokale, rein lesende Modbus-Anbindung und benötigt
Android 8.0 oder neuer.

## Funktionen

- Live-Energiefluss für PV, Haus, Batterie und Netz
- Akkustand, Lade-/Entladeleistung und Betriebszustand
- Netzbezug und Einspeisung getrennt dargestellt
- Erzeugung, Lade-/Entladeenergie, Kapazität und Firmware
- 60-Minuten-Leistungsverlauf
- Material-3-Oberfläche mit Hell-/Dunkelmodus
- Kompakter, stets sichtbarer Widget-Modus
- Automatische Aktualisierung und Wiederverbindung
- Demo-Modus zum Ausprobieren ohne Anlage
- Rein lesender Zugriff: Die App schreibt keine Register

## Solarbank vorbereiten

1. In der Anker-App die Solarbank öffnen.
2. **Einstellungen → Steuerung durch Drittanbieter → Modbus TCP** aktivieren.
3. Die dort angezeigte lokale IP-Adresse notieren.
4. In SOLIX Monitor die IP eintragen. Standardwerte: Port `502`, Geräte-ID `1`.

Eine DHCP-Reservierung im Router verhindert, dass sich die IP später ändert.

## Entwicklung

Voraussetzungen:

- Rust stable
- Tauri 2 CLI: `cargo install tauri-cli --version '^2' --locked`
- Linux: WebKitGTK 4.1 und die üblichen Tauri-Build-Abhängigkeiten
- Windows: Microsoft C++ Build Tools und WebView2

Auf CachyOS/Arch Linux lassen sich die Systemabhängigkeiten zum Beispiel so
installieren:

```bash
sudo pacman -S --needed base-devel webkit2gtk-4.1 libappindicator-gtk3 librsvg
```

Es ist kein Node.js und kein npm nötig. Die Oberfläche besteht aus statischem
HTML, CSS und JavaScript.

```bash
cd src-tauri
cargo tauri dev
```

Backend-Tests:

```bash
cd src-tauri
cargo test
```

Release-Paket:

```bash
cd src-tauri
cargo tauri build
```

Unter Linux entstehen je nach System AppImage-/DEB-/RPM-Pakete, unter Windows
ein MSI- und/oder NSIS-Installer. Alternativ kann der mitgelieferte GitHub-
Actions-Workflow beide Plattformen automatisch bauen.

## Android-APK erstellen

Die einfachste Variante benötigt keine lokale Entwicklungsumgebung:

1. Im GitHub-Repository **Actions → Android Build** öffnen.
2. **Run workflow** wählen und den Lauf starten.
3. Nach erfolgreichem Abschluss den Lauf öffnen.
4. Unter **Artifacts** `SOLIX-Monitor-Android-debug` herunterladen.
5. Das ZIP-Archiv entpacken und `app-debug.apk` auf dem Android-Gerät
   installieren.

Für einen lokalen Build werden JDK 17 sowie Android SDK 36 mit Build Tools
36.0.0 benötigt. Beides lässt sich beispielsweise über Android Studio und den
SDK Manager installieren. Der enthaltene Gradle Wrapper lädt die passende
Gradle-Version selbstständig herunter.

Linux/macOS:

```bash
git clone https://github.com/xNeo92x/SOLIX-Monitor.git
cd SOLIX-Monitor/android
chmod +x gradlew
./gradlew clean testDebugUnitTest assembleDebug
```

Windows PowerShell:

```powershell
git clone https://github.com/xNeo92x/SOLIX-Monitor.git
Set-Location .\SOLIX-Monitor\android
.\gradlew.bat clean testDebugUnitTest assembleDebug
```

Die fertige, automatisch signierte Debug-APK liegt anschließend unter:

```text
android/app/build/outputs/apk/debug/app-debug.apk
```

Installation über ADB, ausgeführt aus dem Ordner `android`:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Falls Android wegen einer abweichenden Signatur kein Update zulässt, muss die
bereits installierte Debug-Version zuerst deinstalliert werden. Dabei werden
deren lokal gespeicherte Einstellungen gelöscht. Für eine dauerhaft
veröffentlichte Release-APK sollte in Android Studio über **Build → Generate
Signed Bundle / APK → APK** ein eigener, sicher verwahrter Signaturschlüssel
verwendet werden. Weitere Android-Hinweise stehen in
[`android/README.md`](android/README.md).

## Datenschutz und Sicherheit

Die Anwendung kommuniziert ausschließlich mit der in den Einstellungen
angegebenen Adresse im lokalen Netz. Es gibt keine Telemetrie, Werbung oder
Cloud-Anmeldung. Gespeichert werden nur Verbindungseinstellungen und lokale
Diagrammwerte; keine Anker-Zugangsdaten. Der Modbus-Client implementiert nur
Lesefunktionen (`FC03`/`FC04`).

## Unterstützte Geräte

Die enthaltene Registerbelegung ist gezielt für die **Solarbank 4 E5000 Pro**
erstellt und gegen Version 1.5.0 der offiziellen Anker-Integration geprüft.
Andere SOLIX-Modelle können abweichende Register verwenden und werden deshalb
absichtlich nicht als kompatibel ausgewiesen.

## Lizenz

MIT. Hinweise zur verwendeten Registerreferenz stehen in
`THIRD_PARTY_NOTICES.md`.
