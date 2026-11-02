# Play Store listing draft

**App name (max 30):** BT BMS Monitor

**Short description (max 80):**
Live Bluetooth battery monitor: current, cell voltages, SoC and alerts.

**Full description (max 4000):**
BT BMS Monitor connects to your battery management system (BMS) over Bluetooth LE and shows what your battery is doing in real time. Ideal for campers, caravans, boats, solar storage and DIY LiFePO4 packs.

• Current gauge — logarithmic ±200 A scale; charging in green, discharging in orange
• Voltage, power and state of charge at a glance in the centre of the gauge
• Individual cell voltages with min/max highlighted, cell delta and balancing indicators
• Temperature sensors reported by the BMS
• Remaining and nominal capacity (Ah)
• Time to full or empty, projected from smoothed current
• Current history graph for the last 30 minutes
• Alerts for low state of charge and cell imbalance, with adjustable thresholds
• Background monitoring: a status notification shows SoC, current and power, with a Disconnect button

Private by design: no account, no ads, no analytics, no internet access. Everything stays on your phone.

Compatibility: works with BMS units that use the FF00 Bluetooth UART protocol; tested to work with JBD BMS.

Note: the BMS accepts only one Bluetooth connection at a time, so close other BMS apps before connecting.

JBD and Xiaoxiang are trademarks of their respective owners. This app is an independent project and is not affiliated with, endorsed by or sponsored by them; the names are used only to describe compatibility.

**Category:** Tools (or Auto & Vehicles)
**Contact email:** alex.kuklin@gmail.com
**Privacy policy URL:** https://alexkuklin.github.io/bt-bms-monitor/privacy/
**Graphics:** `docs/play-store/graphics/icon-512.png` (512×512) and `feature-1024x500.png`; regenerate with `tools/make-store-graphics.py`. Phone screenshots: `docs/screenshots/`.

## Data safety form answers
- Collects or shares user data: **No**
- Data encrypted in transit / deletion request: not applicable (no data collected)

## Foreground service declaration (connectedDevice)
Use: keeps the Bluetooth LE connection to the user's BMS alive while the screen is off, so readings and low-SoC / imbalance alerts keep working. User-visible persistent notification with a Disconnect button.
Video to record: connect to BMS → press Home / lock screen → show notification updating → tap Disconnect.
