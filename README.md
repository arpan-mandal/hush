# Hush

**Your phone. Your peace.**

Hush blocks unwanted phone calls on Android 10 and up. It comes with rules for India’s 140 sales numbers, and you can add your own. The app keeps the list of blocked calls on your phone.

[Download Hush](https://hush.arpanm.xyz/#download) · [Setup guide](https://hush.arpanm.xyz/docs) · [Cloud rules](https://hushdb.arpanm.xyz/rules)

## Getting started

1. Download the signed APK from [Releases](https://github.com/arpan-mandal/hush/releases) or the Hush website. Open it on your phone to install.
2. In Hush, tap **Turn on call blocking** and choose Hush as the caller ID and spam app when Android asks.
3. Check that Home says **Call blocking is on**. Open **Rules → Display rules** for the local and cloud rule table, or use **Try a number** to see what it would do with a sample number.

Android lets you choose one caller ID and spam app at a time. Your regular Phone app still makes and answers calls. If you want an alert after a call is blocked, open **Settings → Blocked call alerts**. On Android 13 or newer, grant notification permission there.

## How the rules work

- **Built-in rules** cover 140 sales calls. You can also turn on filtering for 160 service calls, flagged numbers, or hidden numbers under **What to block**.
- **Your rules** match the whole phone number. For example, `^0?140\d+$` matches local numbers starting with 140 or 0140, followed by more digits. Check a sample with **Try a number** before relying on a rule.
- **Cloud rules** come from the public library. Hush saves a copy so your rules work offline. **Check for new rules** downloads updates. The download request sends no phone numbers, call history, or custom rules.

Flagged numbers rely on a result from your phone company, which Android provides starting with Android 11. Android usually keeps saved contacts out of third-party screening and may withhold calls with hidden numbers. Some phones still show blocked calls in the system call log. Hush handles regular phone calls delivered by Android Telecom.

## After-call feedback

Turn on **After-call feedback** on Home and allow phone-state access and notifications. Once enabled, the card moves to Settings. After an answered incoming call from an unsaved number that Android passes to Hush, Hush can post a silent question. It skips calls it blocked and calls it cannot reliably track.

**No** saves your choice on the phone and stops future questions for that number. **Yes** adds an exact-number local rule and queues a report to HushDB. The report includes the number as an exact-match pattern and a hashed, app-scoped Android identifier. The report may publish the number in the public rule library once HushDB receives three distinct device IDs across at least two source IPs. These checks limit repeat reports. They do not attest physical devices.

Hush checks for a new release at startup. If HushDB returns a different version, the app links to the download page.

## Build from source

Use JDK 17 and install Android SDK Platform 35. Android Studio can set the SDK path for you. For command-line builds, set `ANDROID_HOME` or put `sdk.dir=/path/to/sdk` in an untracked `local.properties`.

```sh
git clone https://github.com/arpan-mandal/hush.git
cd hush
./gradlew testDebugUnitTest assembleDebug
```

The debug APK is at `app/build/outputs/apk/debug/app-debug.apk`. Unit test results are in `app/build/reports/tests/testDebugUnitTest/`. A local debug build has your own signing key. Android checks the signing key when you install an update over an existing copy.

The project uses Kotlin and Jetpack Compose for the UI, Room for rules and history, DataStore for preferences, and RE2/J for number matching. For the call path and local data details, see [How Hush works](docs/how-it-works.md).

## Contributing

Issues and pull requests are welcome. For a call blocking bug, include your Android version, phone model, Hush version, and the relevant setting. Use a made-up number that matches the same pattern so your phone numbers stay private. Rule submissions for the public library go through [the cloud rules site](https://hushdb.arpanm.xyz/).

Hush’s source code is available under the [Apache License 2.0](LICENSE).
