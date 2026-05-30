# Connect to Mac — Implementation Plan

## Architecture Overview

Phone không cần đọc ADB port của chính nó (cần signature-level permission). Thay vào đó:

```
Phone ──POST {phone_ip}──► Mac Daemon (port 7777)
                                │
                         dns-sd discover _adb-tls-connect._tcp
                                │
                         adb connect <phone-ip>:<tls-port>
```

- **Mac**: Python daemon dùng `dns-sd` (macOS builtin) để đăng ký mDNS service `_adb-snap._tcp` và nhận HTTP POST từ phone
- **Android**: User nhập IP Mac thủ công (lưu vào SharedPreferences), bấm Connect → POST phone's IP lên Mac → Mac tự gọi `adb connect`

---

## Quick-Win (v1 — Implemented)

Bỏ NsdManager. User nhập IP Mac một lần, lưu vào SharedPreferences.
- `ConnectToMacRepository` — OkHttp POST + getDeviceLanIp()
- `ConnectToMacViewModel` — StateFlow<ConnectUiState>
- `ConnectToMacScreen` — TextField IP + Connect button
- `HomeScreen` — thêm card "Connect to Mac"
- Mac daemon — `scripts/mac-daemon/adb-snap-daemon.py`

---

## Full Auto-Discovery (v2 — Future)

Dùng `NsdManager` để discover `_adb-snap._tcp` trên LAN.
Mac daemon đăng ký mDNS bằng `dns-sd -R`.

### Android files to create

| File | Purpose |
|---|---|
| `network/MacDevice.kt` | Data class: serviceName, ip, port |
| `network/NsdDiscoveryManager.kt` | Wrap NsdManager, emit DiscoveryEvent qua Channel |
| `network/ConnectToMacRepository.kt` | OkHttp POST + getDeviceLanIp() |
| `ui/viewmodel/ConnectToMacViewModel.kt` | StateFlow + scan timeout 8s |
| `ui/screens/ConnectToMacScreen.kt` | Scanning → List → Connecting → Connected |

### AndroidManifest permissions
```xml
<uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
<uses-permission android:name="android.permission.CHANGE_NETWORK_STATE" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
```

---

## Key Risks

| Rủi ro | Mitigation |
|---|---|
| `NsdManager.FAILURE_ALREADY_ACTIVE` | Fresh ResolveListener mỗi lần; Android 14+ dùng `registerServiceInfoCallback()` |
| Mac không discover ADB TLS port | Fallback port 5555; warning nếu wireless debugging chưa bật |
| macOS Firewall block port 7777 | install.sh nhắc user; Android phân biệt ConnectException vs SocketTimeoutException |

---

## Mac Setup

```bash
cd scripts/mac-daemon
chmod +x install.sh
./install.sh

# Manual start/stop
python3 adb-snap-daemon.py
launchctl list | grep adb-snap
```

**Lưu ý**: Nếu macOS Firewall bật, vào System Settings > Network > Firewall > Options > Add Python > Allow incoming connections.
