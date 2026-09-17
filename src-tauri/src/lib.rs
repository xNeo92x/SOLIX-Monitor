use serde::{Deserialize, Serialize};
use std::fs;
use std::io::{Read, Write};
use std::net::{TcpStream, ToSocketAddrs};
use std::path::PathBuf;
use std::time::{Duration, SystemTime, UNIX_EPOCH};
use tauri::menu::{Menu, MenuItem};
use tauri::tray::{MouseButton, MouseButtonState, TrayIconBuilder, TrayIconEvent};
use tauri::{AppHandle, Manager, WebviewWindow};

const CONFIG_FILE: &str = "settings.json";

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct Settings {
    host: String,
    port: u16,
    unit_id: u8,
    refresh_seconds: u64,
    demo_mode: bool,
}

impl Default for Settings {
    fn default() -> Self {
        Self {
            host: "192.168.178.100".into(),
            port: 502,
            unit_id: 1,
            refresh_seconds: 5,
            demo_mode: true,
        }
    }
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct Snapshot {
    timestamp_ms: u64,
    device_model: String,
    firmware: String,
    connected: bool,
    pv_w: i32,
    load_w: i32,
    battery_w: i32,
    grid_w: i32,
    ac_output_w: i32,
    battery_soc: u16,
    battery_status: String,
    operating_mode: String,
    pv_total_kwh: f64,
    charge_total_kwh: f64,
    discharge_total_kwh: f64,
    rated_energy_kwh: f64,
    max_charge_w: i32,
    max_discharge_w: i32,
}

struct ModbusTcpClient {
    stream: TcpStream,
    transaction_id: u16,
    unit_id: u8,
}

impl ModbusTcpClient {
    fn connect(settings: &Settings) -> Result<Self, String> {
        let target = format!("{}:{}", settings.host.trim(), settings.port);
        let addresses = (settings.host.trim(), settings.port)
            .to_socket_addrs()
            .map_err(|error| format!("Adresse konnte nicht aufgelöst werden: {error}"))?;

        let mut last_error = None;
        for address in addresses {
            match TcpStream::connect_timeout(&address, Duration::from_secs(3)) {
                Ok(stream) => {
                    stream
                        .set_read_timeout(Some(Duration::from_secs(3)))
                        .map_err(|error| error.to_string())?;
                    stream
                        .set_write_timeout(Some(Duration::from_secs(3)))
                        .map_err(|error| error.to_string())?;
                    let _ = stream.set_nodelay(true);
                    return Ok(Self {
                        stream,
                        transaction_id: 0,
                        unit_id: settings.unit_id,
                    });
                }
                Err(error) => last_error = Some(error),
            }
        }

        Err(format!(
            "Keine Verbindung zu {target}: {}",
            last_error
                .map(|error| error.to_string())
                .unwrap_or_else(|| "keine gültige Adresse".into())
        ))
    }

    fn read_input(&mut self, address: u16, count: u16) -> Result<Vec<u16>, String> {
        self.read_registers(0x04, address, count)
    }

    fn read_holding(&mut self, address: u16, count: u16) -> Result<Vec<u16>, String> {
        self.read_registers(0x03, address, count)
    }

    fn read_registers(
        &mut self,
        function: u8,
        address: u16,
        count: u16,
    ) -> Result<Vec<u16>, String> {
        if count == 0 || count > 125 {
            return Err("Ungültige Modbus-Registeranzahl".into());
        }

        self.transaction_id = self.transaction_id.wrapping_add(1);
        let tx = self.transaction_id;
        let mut request = [0_u8; 12];
        request[0..2].copy_from_slice(&tx.to_be_bytes());
        request[2..4].copy_from_slice(&0_u16.to_be_bytes());
        request[4..6].copy_from_slice(&6_u16.to_be_bytes());
        request[6] = self.unit_id;
        request[7] = function;
        request[8..10].copy_from_slice(&address.to_be_bytes());
        request[10..12].copy_from_slice(&count.to_be_bytes());
        self.stream
            .write_all(&request)
            .map_err(|error| format!("Modbus-Anfrage fehlgeschlagen: {error}"))?;

        let mut header = [0_u8; 7];
        self.stream
            .read_exact(&mut header)
            .map_err(|error| format!("Keine Modbus-Antwort: {error}"))?;

        let response_tx = u16::from_be_bytes([header[0], header[1]]);
        let protocol = u16::from_be_bytes([header[2], header[3]]);
        let length = u16::from_be_bytes([header[4], header[5]]) as usize;
        if response_tx != tx || protocol != 0 || header[6] != self.unit_id {
            return Err("Ungültiger Modbus-Antwortkopf".into());
        }
        if !(2..=254).contains(&length) {
            return Err("Ungültige Modbus-Antwortlänge".into());
        }

        let mut payload = vec![0_u8; length - 1];
        self.stream
            .read_exact(&mut payload)
            .map_err(|error| format!("Unvollständige Modbus-Antwort: {error}"))?;

        if payload[0] == (function | 0x80) {
            let code = payload.get(1).copied().unwrap_or(0);
            return Err(format!("Modbus-Ausnahme 0x{code:02X} bei Register {address}"));
        }
        if payload[0] != function || payload.len() < 2 {
            return Err("Unerwartete Modbus-Funktion in der Antwort".into());
        }

        let byte_count = payload[1] as usize;
        let expected = count as usize * 2;
        if byte_count != expected || payload.len() < byte_count + 2 {
            return Err(format!(
                "Falsche Datenlänge: erwartet {expected}, erhalten {byte_count} Byte"
            ));
        }

        Ok(payload[2..2 + byte_count]
            .chunks_exact(2)
            .map(|bytes| u16::from_be_bytes([bytes[0], bytes[1]]))
            .collect())
    }
}

fn register(block: &[u16], start: u16, address: u16) -> Option<u16> {
    block.get(address.checked_sub(start)? as usize).copied()
}

fn int32(block: &[u16], start: u16, address: u16) -> Option<i32> {
    let high = register(block, start, address)? as u32;
    let low = register(block, start, address + 1)? as u32;
    Some(((high << 16) | low) as i32)
}

fn uint32(block: &[u16], start: u16, address: u16) -> Option<u32> {
    let high = register(block, start, address)? as u32;
    let low = register(block, start, address + 1)? as u32;
    Some((high << 16) | low)
}

fn decode_string(registers: &[u16]) -> String {
    let bytes: Vec<u8> = registers
        .iter()
        .flat_map(|value| value.to_be_bytes())
        .take_while(|byte| *byte != 0)
        .collect();
    String::from_utf8_lossy(&bytes).trim().to_string()
}

fn current_time_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as u64
}

fn demo_snapshot() -> Snapshot {
    let now = current_time_ms();
    let phase = (now % 180_000) as f64 / 180_000.0 * std::f64::consts::TAU;
    let pv = (720.0 + phase.sin() * 260.0).max(0.0) as i32;
    let load = (410.0 + (phase * 1.7).cos() * 95.0).max(80.0) as i32;
    let battery = (pv - load - 18).clamp(-800, 800);
    // Positive battery power means discharge, positive grid power means import.
    let grid = load - pv - battery;
    let soc = 68 + ((phase.sin() + 1.0) * 4.0) as u16;
    Snapshot {
        timestamp_ms: now,
        device_model: "Solarbank 4 E5000 Pro".into(),
        firmware: "Demo 1.0.0".into(),
        connected: true,
        pv_w: pv,
        load_w: load,
        battery_w: battery,
        grid_w: grid,
        ac_output_w: (pv - battery).max(0),
        battery_soc: soc.min(100),
        battery_status: if battery > 10 {
            "Entlädt".into()
        } else if battery < -10 {
            "Lädt".into()
        } else {
            "Bereit".into()
        },
        operating_mode: "Eigenverbrauch".into(),
        pv_total_kwh: 426.8,
        charge_total_kwh: 318.4,
        discharge_total_kwh: 286.1,
        rated_energy_kwh: 5.0,
        max_charge_w: 2400,
        max_discharge_w: 2400,
    }
}

fn live_snapshot(settings: &Settings) -> Result<Snapshot, String> {
    if settings.host.trim().is_empty() {
        return Err("Bitte eine lokale IP-Adresse eintragen.".into());
    }
    let mut client = ModbusTcpClient::connect(settings)?;

    // Official Solarbank 4 input ranges. The first range contains every core
    // live value and is therefore treated as the required read.
    let live = client.read_input(10000, 51)?;
    let totals = client.read_input(10208, 58).unwrap_or_default();
    let info = client.read_input(10090, 67).unwrap_or_default();
    let model_regs = client.read_input(32768, 5).unwrap_or_default();
    let controls = client.read_holding(10060, 13).unwrap_or_default();

    let direct_pv = int32(&live, 10000, 10002).unwrap_or(0);
    let third_party_pv = int32(&live, 10000, 10004).unwrap_or(0);
    let battery_w = int32(&live, 10000, 10008).unwrap_or(0);
    let soc = register(&live, 10000, 10014).unwrap_or(0);
    if soc > 100 {
        return Err(format!("Ungültiger Akkustand vom Gerät: {soc}%"));
    }

    let battery_status = match register(&live, 10000, 10001).unwrap_or(0) {
        1 => "Lädt",
        2 => "Entlädt",
        3 => "Schlafmodus",
        _ => "Bereit",
    };
    let operating_mode = match register(&controls, 10060, 10064) {
        Some(0) => "Eigenverbrauch",
        Some(1) => "Zeitplan (TOU)",
        Some(3) => "Drittanbieter-Steuerung",
        Some(4) => "Benutzerdefiniert",
        Some(5) => "Steckdosen-Overlay",
        Some(6) => "Smart-Modus",
        Some(7) => "Dynamischer Tarif",
        _ => "Unbekannt",
    };

    let model_raw = decode_string(&model_regs);
    let firmware = info
        .get(22..28)
        .map(decode_string)
        .filter(|value| !value.is_empty())
        .unwrap_or_else(|| "–".into());

    Ok(Snapshot {
        timestamp_ms: current_time_ms(),
        device_model: if model_raw.is_empty() {
            "Solarbank 4 E5000 Pro".into()
        } else {
            model_raw
        },
        firmware,
        connected: true,
        pv_w: direct_pv.saturating_add(third_party_pv),
        load_w: int32(&live, 10000, 10010).unwrap_or(0),
        battery_w,
        grid_w: int32(&live, 10000, 10012).unwrap_or(0),
        ac_output_w: int32(&totals, 10208, 10208).unwrap_or(0),
        battery_soc: soc,
        battery_status: battery_status.into(),
        operating_mode: operating_mode.into(),
        pv_total_kwh: uint32(&live, 10000, 10018).unwrap_or(0) as f64 / 10.0,
        charge_total_kwh: uint32(&totals, 10208, 10262).unwrap_or(0) as f64 / 10.0,
        discharge_total_kwh: uint32(&totals, 10208, 10264).unwrap_or(0) as f64 / 10.0,
        rated_energy_kwh: uint32(&totals, 10208, 10250).unwrap_or(0) as f64 / 10.0,
        max_charge_w: int32(&live, 10000, 10036).unwrap_or(0),
        max_discharge_w: int32(&live, 10000, 10038).unwrap_or(0),
    })
}

fn config_path(app: &AppHandle) -> Result<PathBuf, String> {
    app.path()
        .app_config_dir()
        .map(|path| path.join(CONFIG_FILE))
        .map_err(|error| error.to_string())
}

#[tauri::command]
fn get_settings(app: AppHandle) -> Settings {
    config_path(&app)
        .ok()
        .and_then(|path| fs::read_to_string(path).ok())
        .and_then(|json| serde_json::from_str(&json).ok())
        .unwrap_or_default()
}

#[tauri::command]
fn save_settings(app: AppHandle, settings: Settings) -> Result<(), String> {
    if settings.port == 0
        || settings.unit_id == 0
        || settings.unit_id > 247
        || settings.refresh_seconds < 2
        || settings.refresh_seconds > 60
    {
        return Err("Port, Geräte-ID oder Aktualisierungsintervall ist ungültig.".into());
    }
    let path = config_path(&app)?;
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent).map_err(|error| error.to_string())?;
    }
    let json = serde_json::to_string_pretty(&settings).map_err(|error| error.to_string())?;
    fs::write(path, json).map_err(|error| error.to_string())
}

#[tauri::command]
fn read_snapshot(settings: Settings) -> Result<Snapshot, String> {
    if settings.demo_mode {
        Ok(demo_snapshot())
    } else {
        live_snapshot(&settings)
    }
}

#[tauri::command]
fn set_widget_mode(window: WebviewWindow, enabled: bool) -> Result<(), String> {
    window
        .set_always_on_top(enabled)
        .map_err(|error| error.to_string())?;
    window
        .set_resizable(!enabled)
        .map_err(|error| error.to_string())?;
    let size = if enabled {
        tauri::LogicalSize::new(420.0, 620.0)
    } else {
        tauri::LogicalSize::new(1180.0, 790.0)
    };
    window.set_size(size).map_err(|error| error.to_string())
}

pub fn run() {
    tauri::Builder::default()
        .setup(|app| {
            let open = MenuItem::with_id(app, "open", "SOLIX Monitor öffnen", true, None::<&str>)?;
            let quit = MenuItem::with_id(app, "quit", "Beenden", true, None::<&str>)?;
            let menu = Menu::with_items(app, &[&open, &quit])?;

            TrayIconBuilder::new()
                .icon(tauri::include_image!("icons/32x32.png"))
                .tooltip("SOLIX Monitor")
                .menu(&menu)
                .show_menu_on_left_click(false)
                .on_menu_event(|app, event| match event.id.as_ref() {
                    "open" => {
                        if let Some(window) = app.get_webview_window("main") {
                            let _ = window.show();
                            let _ = window.set_focus();
                        }
                    }
                    "quit" => app.exit(0),
                    _ => {}
                })
                .on_tray_icon_event(|tray, event| {
                    if let TrayIconEvent::Click {
                        button: MouseButton::Left,
                        button_state: MouseButtonState::Up,
                        ..
                    } = event
                    {
                        if let Some(window) = tray.app_handle().get_webview_window("main") {
                            let _ = window.show();
                            let _ = window.set_focus();
                        }
                    }
                })
                .build(app)?;
            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            get_settings,
            save_settings,
            read_snapshot,
            set_widget_mode
        ])
        .run(tauri::generate_context!())
        .expect("SOLIX Monitor konnte nicht gestartet werden");
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn decodes_big_endian_signed_values() {
        assert_eq!(int32(&[0x0000, 0x012C], 100, 100), Some(300));
        assert_eq!(int32(&[0xFFFF, 0xFED4], 100, 100), Some(-300));
    }

    #[test]
    fn decodes_strings() {
        assert_eq!(decode_string(&[0x4131, 0x3758, 0x3800]), "A17X8");
    }
}
