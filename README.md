# SocialGeniex SMS Gateway (Android)

Turns an Android phone into an SMS gateway for SocialGeniex. The phone polls
the server for queued SMS jobs, sends them through its own SIM card(s), and
reports delivery — so sending SMS costs whatever the SIM plan costs (e.g. a
cheap local SMS bundle), with no per-message gateway fees.

**Package:** `com.socialgeniex.smsgateway`
**Launcher name:** SocialGeniex SMS
**Language:** Kotlin, XML views (no Compose) · **minSdk 26, targetSdk 34**

## How it works

1. In SocialGeniex (server) an admin creates an SMS device gateway and shows
   a QR code. The QR contains JSON: `{"u": "<server base url>", "t": "<device_token>"}`
2. The user installs this app, taps through 3 onboarding steps
   (permissions → scan QR → done), and the phone links itself via
   `POST /api/sms/gateway/register`.
3. A persistent foreground service polls `GET /api/sms/gateway/pull` (every
   20s by default, or the server's `poll_interval`), sends each SMS with
   `SmsManager` on the requested SIM slot, and reports
   `sent` / `failed` / `delivered` per job to `POST /api/sms/gateway/report`.
4. Incoming SMS are forwarded to `POST /api/sms/gateway/inbound` so replies
   land in the SocialGeniex team inbox.
5. A heartbeat (`POST /api/sms/gateway/heartbeat`) goes out every 5 minutes
   with battery % and the SIM list.

Safety features: per-SIM daily quota (`per_sim_daily_quota`, 0 = unlimited,
resets at midnight — over-quota jobs are left unreported so the server
re-queues them), configurable delay between messages (`delay_ms`),
multi-part SMS tracked per part (a job is `sent` only when every part is
accepted by the radio).

## Build

Requirements: JDK 17, Android SDK with **platform 34** and build-tools.

```bash
cd sg-sms-gateway-app
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

> No `gradlew` script or `local.properties` is bundled — generate the wrapper
> once on a machine with Gradle installed (`gradle wrapper --gradle-version 8.2`)
> or copy one in. Point the SDK via `ANDROID_HOME` / `ANDROID_SDK_ROOT` or a
> `local.properties` file (`sdk.dir=/path/to/android-sdk`).

Release: `./gradlew assembleRelease` (minify is off; sign with your own key).

## Push notifications (optional)

The app compiles and runs **without** `google-services.json`. Firebase
auto-init is disabled in the manifest and all FCM calls are guarded — without
a Firebase project the token is simply `null` and everything else works
(polling keeps the gateway alive).

To enable server wake-up pushes:

1. Add `google-services.json` to `app/`
2. Apply the google-services plugin in `app/build.gradle`
3. Remove the `tools:node="remove"` on `FirebaseInitProvider` in
   `AndroidManifest.xml`

## Permissions & why

| Permission | Why (shown in-app in plain language) |
|---|---|
| SEND_SMS | Send text messages from the SIM |
| READ_SMS / RECEIVE_SMS | Forward incoming replies to SocialGeniex |
| READ_PHONE_STATE / READ_PHONE_NUMBERS | Detect SIM slots for dual-SIM sending |
| CAMERA | Scan the linking QR code |
| POST_NOTIFICATIONS (13+) | The persistent "gateway active" notification |
| FOREGROUND_SERVICE (+ SPECIAL_USE) | Keep the gateway running |
| RECEIVE_BOOT_COMPLETED | Restart after reboot |
| REQUEST_IGNORE_BATTERY_OPTIMIZATIONS | Ask to exempt the app from battery limits |

On first launch the app asks the user to exempt it from battery
optimization — without this, Android may pause background work.

## Project layout

```
app/src/main/
├── AndroidManifest.xml
├── java/com/socialgeniex/smsgateway/
│   ├── MainActivity.kt          # glanceable status screen
│   ├── OnboardingActivity.kt    # 3-step link flow (permissions → QR → done)
│   ├── GatewayService.kt        # foreground worker: poll → send → report → heartbeat
│   ├── SmsReceiver.kt           # incoming SMS → /inbound
│   ├── SmsStatusReceiver.kt     # per-part sent/delivered radio callbacks
│   ├── BootReceiver.kt          # restart after reboot
│   ├── GatewayFirebaseService.kt# optional push wake-ups (guarded stub)
│   ├── ApiClient.kt             # OkHttp wrapper for the gateway API
│   ├── SimHelper.kt             # dual-SIM discovery + routing
│   ├── JobTracker.kt            # multi-part send tracking
│   ├── Prefs.kt                 # SharedPreferences: link, settings, stats, quotas
│   ├── LogStore.kt              # last-50-events ring buffer for the log view
│   └── PlainErrors.kt           # every error in plain language, no jargon
└── res/                         # SocialGeniex brand: deep green #043B2C, lime #C8F04A
```

## Notes / known limits

- Dual-SIM routing uses `SmsManager.getSmsManagerForSubscriptionId()`; the
  server picks the slot per job (`sim_slot`), otherwise the default SIM.
- Delivery reports depend on the carrier — `sent` is reliable everywhere,
  `delivered` arrives when the network supports it.
- Sideload the APK (this app uses SMS permissions that need Play
  declarations for store publishing).
