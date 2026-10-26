# Dongle CarPlay Integration — Test State

## What works (proven in logs)

1. **USB role switch (0x51)** — dongle accepts, dwc3 switches to device mode
2. **FunctionFS gadget (g3)** — dongle enumerates iAP2 + NCM interfaces
3. **iAP2 link** — using `openWireless()` (dongle initiates SYN), link ready in ~0.6s
4. **Identification** — dongle sends StartIdentification (0x1D00), we reply with IdentificationInformation, dongle accepts (0x1D02)
5. **MFi authentication** — full handshake in ~0.2s: certificate → challenge → signature → AuthenticationSucceeded (0xAA05)
6. **Power + Subscriptions** — PowerSourceUpdate (500mA) + 5 subscription frames sent and accepted
7. **CarPlayAvailability (0x4300)** — dongle sends `wiredAvailable=true` with USB identifier `18091ea76865b5e827a9e6d2ef62d69222a93e99`
8. **CarPlayStartSession (0x4301)** — sent with `fe80::2`, port 7000, Ed25519 pubkey, sourceVersion `950.7.1`
9. **iPhone responds** — NowPlayingUpdate (0x5001), RouteGuidanceUpdate (0x5201), PowerUpdate (0xAE01) arrive immediately

## What fails

- **iAP2 link dies ~16s after CarPlayStartSession** — "iAP2 packet 109 was not acknowledged"
- **Root cause**: iPhone tries to connect via AirPlay over the NCM network interface to `fe80::2:7000`. No NCM interface was configured and no AirPlay server was listening, so the iPhone gives up and the iAP2 link times out.

## Next steps to fix

1. **Configure NCM network interface** — `usb0` appears when gadget is enabled. Needs:
   ```sh
   ip link set usb0 up
   ip -6 addr add fe80::2/64 dev usb0
   ```
   cpbridge.c was updated to call `configure_ncm_interface()` on FUNCTIONFS_ENABLE event (already compiled but not tested yet).

2. **AirPlay server** — DiPlay has a built-in AirPlay server. The test activity needs to either:
   - Start DiPlay's AirPlay server on port 7000 bound to `fe80::2`, OR
   - Use a TCP proxy/socat from `fe80::2:7000` to DiPlay's AirPlay server on localhost

3. **Keep iAP2 link alive** — once AirPlay connects, the iPhone will stream CarPlay video/audio over it. The iAP2 control channel must stay alive for the duration.

## Architecture

```
iPhone ←→ Dongle ←USB(0x51 role-switch)→ Android (gadget g3)
                                           ├── FunctionFS (iap2) ←→ cpbridge ←TCP:19876→ DiPlay (iAP2/MFi/CarPlay control)
                                           └── NCM (usb0, fe80::2) ←→ iPhone AirPlay client → DiPlay AirPlay server (port 7000)
```

## Files on Android device (`/data/local/tmp/`)

| File | Purpose |
|------|---------|
| `cpbridge` | Socket bridge binary (latest: with NCM config on FUNCTIONFS_ENABLE) |
| `setup.sh` | Creates configfs gadget g3 (iAP2 FunctionFS + CDC-NCM) |
| `cpswitch` | Old standalone role-switch tool |
| `cpswitch2` | Old standalone tool with iAP2 detect |

## Files in project

| File | Purpose |
|------|---------|
| `mobile/src/debug/java/com/shilapi/xcertplay/dongle/DongleBridgeTestActivity.kt` | Test activity: full iAP2 + MFi + CarPlay control flow |
| `mobile/src/debug/AndroidManifest.xml` | Registers DongleBridgeTestActivity |
| `shared/src/main/java/com/shilapi/xcertplay/transport/SocketDuplexByteStream.kt` | TCP socket BlockingDuplexByteStream |

## Source files in scratchpad

| File | Purpose |
|------|---------|
| `scratchpad/cpswitch/cpbridge.c` | Bridge source (latest: with `configure_ncm_interface()`) |
| `scratchpad/cpswitch/setup.sh` | Gadget setup script source |

## Key DiPlay source files

| File | Key APIs |
|------|----------|
| `shared/.../transport/Iap2WiredControlClient.kt` | `powerSourceUpdate(mA)`, `subscriptions()`, `carPlayAvailabilitySummary()` |
| `shared/.../transport/Iap2WiredCarPlayEndpoint.kt` | Data class: ipv6Addresses, airPlayPort, publicKey, sourceVersion, deviceIdentifier |
| `shared/.../iap2/message/Iap2CarPlayMessages.kt` | `startSession(wiredIpv6Addresses, airPlayPort, publicKey, sourceVersion, deviceIdentifier)` |
| `shared/.../iap2/session/Iap2Session.kt` | `openWireless()`, `send()`, `recv()`, `awaitReady()` |
| `shared/.../transport/Iap2LinkChannel.kt` | `openWireless()` = initiateNegotiation=false, WIRELESS_LINK_CONFIG |
| `shared/.../airplay/AirPlayIdentity.kt` | `generate()` → Ed25519 keypair, `publicKeyHex` |
| `shared/.../mfi/LocalMfiAuthenticationClient.kt` | `load(dir)`, `readCertificate()`, `signChallenge()` |
| `shared/.../orchestration/CarPlayRuntimeConfig.kt` | `linkLocal` defaults to `fe80::2` |

## Test commands

```sh
# 1. Clean up old run
adb shell 'su -c "pkill cpbridge; echo host > /sys/class/usb_role/a600000.ssusb-role-switch/role; echo '' > /config/usb_gadget/g3/UDC"'

# 2. Full cleanup + recreate gadget
adb shell 'su -c "rm /config/usb_gadget/g3/configs/c.1/f1; rm /config/usb_gadget/g3/configs/c.1/f2; umount /dev/usb-ffs/iap2; rmdir /dev/usb-ffs/iap2; rmdir /dev/usb-ffs; rmdir /config/usb_gadget/g3/configs/c.1/strings/0x409; rmdir /config/usb_gadget/g3/configs/c.1; rmdir /config/usb_gadget/g3/functions/ffs.iap2; rmdir /config/usb_gadget/g3/functions/ncm.cp; rmdir /config/usb_gadget/g3/strings/0x409; rmdir /config/usb_gadget/g3"'
adb shell 'su -c "sh /data/local/tmp/setup.sh"'

# 3. Start cpbridge (waits for TCP connection before role switch)
adb shell 'su -c "/data/local/tmp/cpbridge /dev/usb-ffs/iap2 19876"'

# 4. In another terminal, launch test activity
adb shell am start -n com.shihab.diplay.hudtest/com.shilapi.xcertplay.dongle.DongleBridgeTestActivity

# 5. Watch logs
adb logcat -s DongleBridge:*
```

## Build commands

```sh
# APK (needs JAVA_TOOL_OPTIONS for JDK 25 on Windows)
set JAVA_TOOL_OPTIONS="-Djdk.net.unixdomain.tmpdir=C:/tmp"
./gradlew :mobile:assembleDebug
adb install -r mobile/build/outputs/apk/debug/mobile-debug.apk

# cpbridge native binary
NDK="C:/Users/khanhtran/AppData/Local/Android/Sdk/ndk/29.0.14206865"
CC="$NDK/toolchains/llvm/prebuilt/windows-x86_64/bin/aarch64-linux-android21-clang"
$CC -o cpbridge cpbridge.c -static -O2
adb push cpbridge /data/local/tmp/cpbridge
adb shell 'su -c "chmod 755 /data/local/tmp/cpbridge"'
```

## Successful test log (2026-10-26 19:55)

```
iAP2 link ready
first message: id=0x1d00 payload=0B
dongle sent StartIdentification — sending IdentificationInformation
identification response: 0x1d02
identification accepted, running MFi auth
*** MFi AUTHENTICATION SUCCEEDED ***
=== PHASE: Power + Subscriptions ===
sent PowerSourceUpdate (500mA)
sent subscription 0x5000 (NowPlaying)
sent subscription 0x5200 (RouteGuidance)
sent subscription 0xae00 (Power)
sent subscription 0x4157 (Communications)
sent subscription 0x4154 (CallState)
all subscriptions sent
=== PHASE: Waiting for CarPlay messages ===
rx 0x4300 payload=54B — CarPlay availability received
  wiredAvailable=true usbTransport=18091ea76865b5e827a9e6d2ef62d69222a93e99
*** SENT CarPlayStartSession (0x4301) ***
  ipv6=[fe80::2], port=7000, deviceId=AA:BB:CC:DD:EE:FF
rx 0x5001 — NowPlayingUpdate (iPhone responding!)
rx 0x5001 — NowPlayingUpdate (app info: com.l.y)
rx 0x5201 — RouteGuidanceUpdate
rx 0xae01 — PowerUpdate (charging=1)
[16s later] ERROR: IOException: iAP2 packet 109 was not acknowledged
```

## Key technical notes

- **Use `openWireless()` not `open()`** — `open()` sends SYN (initiator), dongle responds with 2 SYN+ACK frames causing a sequence gap. `openWireless()` lets dongle initiate.
- **Don't use `Iap2IdentificationClient.identify()`** — it internally calls `recv()` waiting for StartIdentification which we already consumed. Send IdentificationInformation directly.
- **`Iap2ParameterList.parameters` is private** — use `asList()`, `first(id)`, `require(id)`.
- **cpbridge accepts TCP BEFORE role switch** — critical timing: dongle sends iAP2 detect within ~0.6s of gadget enable, so the bridge must already be connected.
- **Gradle on Windows with JDK 25** — needs `JAVA_TOOL_OPTIONS="-Djdk.net.unixdomain.tmpdir=C:/tmp"` to fix UnixDomainSockets.connect0 error.
- **Activity component name** — `com.shihab.diplay.hudtest/com.shilapi.xcertplay.dongle.DongleBridgeTestActivity` (app package differs from class package).
