# Galaxy Charge Monitor

[日本語](README.md) · English

Read charging telemetry and the controls limiting charging speed on a Galaxy. The app does not change charging settings or thermal protection.

## What it shows

- Reasons for charging speed: distinguishes reported thermal-control selection, matching staged-charging settings, and constant-voltage mode. Inferences are labeled.
- Net battery-side power, voltage, current, charge level, and battery/LRP/charger-IC/USB temperatures.
- Direct-charging state and stage, input-current and battery-current limits, active control votes, and selected results.
- Device thermal settings and recovery temperatures, comparing LRP tables such as 45 W and 60 W separately for screen-on/off conditions.
- A searchable list of charging settings read from the device, including original bytes.
- Session history graphs and explicit JSON export.
- Japanese/English UI, Material 3 light/dark themes, and searchable licenses.

## Setup

1. Install the signed release APK from [Releases](https://github.com/OneUI-Mods/GalaxyChargeMonitor/releases).
2. Open the app, select **Connect with Root**, and approve it in your root manager. Xposed / Vector registration and a reboot are not required.
3. Detailed temperatures, controls, and setting tables become available. Android's basic telemetry is available without root.

Installation requires Android 15 or later. Detailed interpretation targets Galaxy S26 Ultra (SM-S948Q), Android 17 / One UI 9 BZI9. Available readings can also be shown on Android 16 and other environments, but coverage varies by device and firmware. Missing readings are not filled with values from another device.

## Interpreting values

Battery voltage × current is not USB input power or a physical wattmeter reading at the charger. Input-side ICL and battery-side FCC are different values. The 45 W / 60 W tables are for comparison; measured wattage is not used to decide which table is active. Exceeding a temperature condition is shown separately from an actually active control vote.

A setting's existence does not establish that its branch is currently used. Some array-index-to-condition mappings are unresolved. The app shows timestamps and whether readings are fresh or cached. Setting tables are stored in this app's own internal data.

## Power and data

Monitoring runs only while the app is visible and stops in the background. There is no resident service or wake lock. The interval is configurable; while not charging, it is at least ten seconds. Up to 360 history points are kept in memory, without interpolating periods when monitoring stopped.

The app has no network permission. JSON is exported only to a location you explicitly choose. The app does not write charging controls, system settings, services, or thermal protection.

See the [developer guide (Japanese)](docs/DEVELOPMENT.md) for builds, validation, and signing. The app uses the [MIT License](LICENSE); dependency licenses are under **About**. This is not an official Samsung app.
