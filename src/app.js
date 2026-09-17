"use strict";

const $ = (selector) => document.querySelector(selector);
const tauriInvoke = window.__TAURI__?.core?.invoke;

let settings = {
  host: "192.168.178.100",
  port: 502,
  unitId: 1,
  refreshSeconds: 5,
  demoMode: true,
};
let refreshTimer = null;
let polling = false;
let widgetMode = false;
let history = loadHistory();
let toastTimer = null;

function loadHistory() {
  try {
    const parsed = JSON.parse(localStorage.getItem("solix-history") || "[]");
    return Array.isArray(parsed) ? parsed.filter((point) => Date.now() - point.t < 3_600_000) : [];
  } catch {
    return [];
  }
}

function saveHistory() {
  try { localStorage.setItem("solix-history", JSON.stringify(history)); } catch { /* private mode */ }
}

function demoSnapshot() {
  const now = Date.now();
  const phase = (now % 180000) / 180000 * Math.PI * 2;
  const pv = Math.max(0, Math.round(720 + Math.sin(phase) * 260));
  const load = Math.max(80, Math.round(410 + Math.cos(phase * 1.7) * 95));
  const battery = Math.max(-800, Math.min(800, pv - load - 18));
  return {
    timestampMs: now, deviceModel: "Solarbank 4 E5000 Pro", firmware: "Browser-Demo",
    connected: true, pvW: pv, loadW: load, batteryW: battery, gridW: load - pv - battery,
    acOutputW: Math.max(0, pv - battery), batterySoc: Math.min(100, 68 + Math.round((Math.sin(phase) + 1) * 4)),
    batteryStatus: battery > 10 ? "Entlädt" : battery < -10 ? "Lädt" : "Bereit",
    operatingMode: "Eigenverbrauch", pvTotalKwh: 426.8, chargeTotalKwh: 318.4,
    dischargeTotalKwh: 286.1, ratedEnergyKwh: 5.0, maxChargeW: 2400, maxDischargeW: 2400,
  };
}

async function invoke(command, args = {}) {
  if (tauriInvoke) return tauriInvoke(command, args);
  if (command === "get_settings") {
    try { return JSON.parse(localStorage.getItem("solix-settings")) || settings; } catch { return settings; }
  }
  if (command === "save_settings") {
    localStorage.setItem("solix-settings", JSON.stringify(args.settings));
    return;
  }
  if (command === "read_snapshot") {
    if (!args.settings.demoMode) throw new Error("Der echte Modbus-Zugriff steht nur in der Desktop-App zur Verfügung.");
    return demoSnapshot();
  }
}

function watts(value) {
  const number = Math.abs(Number(value) || 0);
  return number >= 1000 ? `${(number / 1000).toFixed(number >= 10000 ? 1 : 2)} kW` : `${Math.round(number)} W`;
}

function energy(value) {
  const number = Number(value) || 0;
  return number >= 1000 ? `${(number / 1000).toFixed(2)} MWh` : `${number.toFixed(1)} kWh`;
}

function text(id, value) { $(id).textContent = value; }

function setPath(id, active, reverse = false) {
  const path = $(id);
  path.classList.toggle("active", active);
  path.classList.toggle("reverse", reverse);
}

function renderSnapshot(data) {
  text("#pv-value", watts(data.pvW));
  text("#load-value", watts(data.loadW));
  text("#battery-value", watts(data.batteryW));
  text("#grid-value", watts(data.gridW));
  text("#battery-flow-label", data.batteryW > 10 ? "Entladung" : data.batteryW < -10 ? "Ladung" : "Batterie");
  text("#grid-flow-label", data.gridW > 10 ? "Netzbezug" : data.gridW < -10 ? "Einspeisung" : "Netz");
  text("#soc-value", data.batterySoc);
  text("#battery-status", data.batteryStatus);
  text("#capacity-value", `${Number(data.ratedEnergyKwh || 0).toFixed(1)} kWh`);
  text("#mode-value", data.operatingMode || "—");
  text("#pv-total", energy(data.pvTotalKwh));
  text("#charge-total", energy(data.chargeTotalKwh));
  text("#discharge-total", energy(data.dischargeTotalKwh));
  text("#ac-output", watts(data.acOutputW));
  text("#device-model", data.deviceModel || "Solarbank 4 E5000 Pro");
  text("#firmware-value", data.firmware || "—");
  text("#max-charge", watts(data.maxChargeW));
  text("#max-discharge", watts(data.maxDischargeW));
  text("#source-value", settings.demoMode ? "Demo-Daten" : "Lokal · Modbus TCP");
  text("#updated-at", new Date(data.timestampMs).toLocaleTimeString("de-DE", { hour: "2-digit", minute: "2-digit", second: "2-digit" }));
  $("#soc-ring").style.setProperty("--soc", Math.max(0, Math.min(100, data.batterySoc)));

  setPath("#path-pv", data.pvW > 5, false);
  setPath("#path-home", data.loadW > 5, false);
  setPath("#path-battery", Math.abs(data.batteryW) > 5, data.batteryW > 0);
  setPath("#path-grid", Math.abs(data.gridW) > 5, data.gridW > 0);

  const pill = $("#connection-pill");
  pill.className = "status-pill online";
  pill.querySelector("b").textContent = settings.demoMode ? "Demo aktiv" : "Lokal verbunden";

  history.push({ t: data.timestampMs, pv: data.pvW, load: data.loadW, battery: data.batteryW, grid: data.gridW });
  const cutoff = Date.now() - 3_600_000;
  history = history.filter((point, index, all) => point.t >= cutoff && (index === all.length - 1 || all[index + 1].t !== point.t));
  const maxPoints = Math.min(1800, Math.max(120, Math.ceil(3600 / settings.refreshSeconds) + 4));
  if (history.length > maxPoints) history = history.slice(-maxPoints);
  saveHistory();
  drawChart();
}

function renderError(error) {
  const pill = $("#connection-pill");
  pill.className = "status-pill error";
  pill.querySelector("b").textContent = "Nicht verbunden";
  text("#updated-at", "Verbindung unterbrochen");
  setPath("#path-pv", false); setPath("#path-home", false); setPath("#path-battery", false); setPath("#path-grid", false);
  showToast(String(error).replace(/^Error:\s*/, ""));
}

async function poll() {
  if (polling) return;
  polling = true;
  $("#refresh-button").classList.add("spinning");
  try {
    const data = await invoke("read_snapshot", { settings });
    renderSnapshot(data);
  } catch (error) {
    renderError(error);
  } finally {
    polling = false;
    $("#refresh-button").classList.remove("spinning");
  }
}

function schedulePolling() {
  clearInterval(refreshTimer);
  refreshTimer = setInterval(poll, settings.refreshSeconds * 1000);
}

function showToast(message) {
  const toast = $("#toast");
  toast.textContent = message;
  toast.classList.add("show");
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => toast.classList.remove("show"), 3600);
}

function fillSettingsForm() {
  $("#host-input").value = settings.host;
  $("#port-input").value = settings.port;
  $("#unit-input").value = settings.unitId;
  $("#refresh-input").value = String(settings.refreshSeconds);
  $("#demo-input").checked = settings.demoMode;
  toggleNetworkFields();
}

function readSettingsForm() {
  return {
    host: $("#host-input").value.trim(),
    port: Number($("#port-input").value),
    unitId: Number($("#unit-input").value),
    refreshSeconds: Number($("#refresh-input").value),
    demoMode: $("#demo-input").checked,
  };
}

function toggleNetworkFields() {
  const disabled = $("#demo-input").checked;
  ["#host-input", "#port-input", "#unit-input"].forEach((id) => $(id).disabled = disabled);
}

async function testConnection() {
  const result = $("#test-result");
  const button = $("#test-button");
  button.disabled = true;
  result.hidden = false;
  result.className = "test-result";
  result.textContent = "Verbindung wird geprüft …";
  try {
    const candidate = readSettingsForm();
    const snapshot = await invoke("read_snapshot", { settings: candidate });
    result.className = "test-result success";
    result.textContent = `Verbindung erfolgreich · ${snapshot.deviceModel} · ${snapshot.batterySoc}%`;
  } catch (error) {
    result.className = "test-result error";
    result.textContent = String(error).replace(/^Error:\s*/, "");
  } finally {
    button.disabled = false;
  }
}

async function saveSettings(event) {
  event.preventDefault();
  const candidate = readSettingsForm();
  if (!candidate.demoMode && !candidate.host) return showToast("Bitte eine IP-Adresse eintragen.");
  try {
    await invoke("save_settings", { settings: candidate });
    settings = candidate;
    history = [];
    saveHistory();
    $("#settings-dialog").close();
    schedulePolling();
    await poll();
    showToast("Einstellungen gespeichert");
  } catch (error) { showToast(String(error)); }
}

function applyTheme(theme) {
  const resolved = theme === "system" ? (matchMedia("(prefers-color-scheme: dark)").matches ? "dark" : "light") : theme;
  document.documentElement.dataset.theme = resolved;
  localStorage.setItem("solix-theme", theme);
}

function cycleTheme() {
  const current = localStorage.getItem("solix-theme") || "system";
  const next = current === "system" ? "dark" : current === "dark" ? "light" : "system";
  applyTheme(next);
  showToast(next === "system" ? "Systemdarstellung" : next === "dark" ? "Dunkle Darstellung" : "Helle Darstellung");
  drawChart();
}

async function toggleWidgetMode() {
  widgetMode = !widgetMode;
  document.body.classList.toggle("widget-mode", widgetMode);
  $("#widget-button").classList.toggle("tonal", widgetMode);
  try { await invoke("set_widget_mode", { enabled: widgetMode }); } catch { /* browser preview */ }
  setTimeout(drawChart, 80);
}

function drawChart() {
  const canvas = $("#power-chart");
  const empty = $("#chart-empty");
  if (!canvas || history.length < 2) { if (empty) empty.hidden = false; return; }
  empty.hidden = true;
  const rect = canvas.getBoundingClientRect();
  const ratio = Math.max(1, window.devicePixelRatio || 1);
  canvas.width = Math.round(rect.width * ratio);
  canvas.height = Math.round(rect.height * ratio);
  const ctx = canvas.getContext("2d");
  ctx.scale(ratio, ratio);
  const width = rect.width, height = rect.height;
  const pad = { left: 42, right: 8, top: 8, bottom: 23 };
  const values = history.flatMap((point) => [point.pv, point.load, point.battery, point.grid]);
  const peak = Math.max(500, ...values.map(Math.abs));
  const nicePeak = Math.ceil(peak / 500) * 500;
  const yMin = Math.min(0, ...values) < 0 ? -nicePeak : 0;
  const yMax = nicePeak;
  const plotW = width - pad.left - pad.right;
  const plotH = height - pad.top - pad.bottom;
  const css = getComputedStyle(document.documentElement);
  const mapY = (value) => pad.top + (yMax - value) / (yMax - yMin) * plotH;
  const mapX = (time) => pad.left + (time - (Date.now() - 3_600_000)) / 3_600_000 * plotW;

  ctx.clearRect(0, 0, width, height);
  ctx.font = "10px system-ui";
  ctx.textAlign = "right";
  ctx.textBaseline = "middle";
  const gridColor = css.getPropertyValue("--outline-soft").trim();
  const labelColor = css.getPropertyValue("--on-surface-variant").trim();
  ctx.strokeStyle = gridColor; ctx.fillStyle = labelColor; ctx.globalAlpha = .55;
  for (let i = 0; i <= 4; i++) {
    const value = yMin + (yMax - yMin) * i / 4;
    const y = mapY(value);
    ctx.beginPath(); ctx.moveTo(pad.left, y); ctx.lineTo(width - pad.right, y); ctx.stroke();
    ctx.fillText(`${Math.round(value / 100) / 10}k`, pad.left - 7, y);
  }
  ctx.globalAlpha = 1;
  const datasets = [
    ["pv", "--solar"], ["load", "--home"], ["battery", "--violet"], ["grid", "--grid"],
  ];
  for (const [key, color] of datasets) {
    ctx.beginPath(); ctx.lineWidth = 2; ctx.lineJoin = "round"; ctx.lineCap = "round";
    ctx.strokeStyle = css.getPropertyValue(color).trim();
    history.forEach((point, index) => {
      const x = mapX(point.t), y = mapY(point[key]);
      if (index === 0) ctx.moveTo(x, y); else ctx.lineTo(x, y);
    });
    ctx.stroke();
  }
  ctx.fillStyle = labelColor; ctx.font = "10px system-ui";
  ctx.textAlign = "center"; ctx.textBaseline = "bottom";
  const now = Date.now();
  [60, 30, 0].forEach((minutes) => {
    const time = now - minutes * 60_000;
    const x = pad.left + (60 - minutes) / 60 * plotW;
    ctx.fillText(new Date(time).toLocaleTimeString("de-DE", { hour: "2-digit", minute: "2-digit" }), x, height);
  });
}

async function init() {
  applyTheme(localStorage.getItem("solix-theme") || "system");
  try { settings = { ...settings, ...(await invoke("get_settings")) }; } catch { /* defaults */ }
  fillSettingsForm();
  $("#settings-button").addEventListener("click", () => { fillSettingsForm(); $("#test-result").hidden = true; $("#settings-dialog").showModal(); });
  $("#settings-form").addEventListener("submit", saveSettings);
  $("#demo-input").addEventListener("change", toggleNetworkFields);
  $("#test-button").addEventListener("click", testConnection);
  $("#refresh-button").addEventListener("click", poll);
  $("#theme-button").addEventListener("click", cycleTheme);
  $("#widget-button").addEventListener("click", toggleWidgetMode);
  window.addEventListener("resize", () => requestAnimationFrame(drawChart));
  window.addEventListener("keydown", (event) => { if (event.key === "Escape" && widgetMode && !$("#settings-dialog").open) toggleWidgetMode(); });
  schedulePolling();
  await poll();
}

init();
