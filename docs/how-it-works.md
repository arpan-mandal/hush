# How Hush works

The Android app is in `app/`. The Kotlin package is `com.vertos.promoblocker`.

When Android Telecom sends an incoming call to `PromoCallScreeningService`, the service reads the latest rules from a snapshot already in memory. `NumberEvaluator` checks the settings and matches the phone number against compiled RE2/J patterns. The service tells Telecom to allow or reject the call. If the rules are still loading or evaluation takes too long, a local watchdog lets the call through.

`BlockerRepository` publishes the snapshot from Room and DataStore. After a rejection response, it records the call and can post a silent notification. A blocked call entry contains the number, time, and reason. Hush keeps up to 10,000 entries and shows the latest 1,000 in History. **Clear history** deletes the entries from Hush’s database. Android backup for this data is disabled.

`CloudRules.kt` downloads the approved HTTPS feed. Updates can run when the app opens, when connectivity returns while the app is running, and through WorkManager. Android decides when a background job runs. The latest saved rules are available offline. Hush keeps your choice to turn individual cloud rules off when the feed updates.

The tests cover number matching, the response sent to Telecom, storage and upgrades, notifications, and the Compose UI. Robolectric covers Android behavior in the JVM. Carrier checks, call logs, and the timing of real calls also depend on the phone and network.
