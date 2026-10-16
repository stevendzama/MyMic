# Live Mic

An Android app that turns your phone's built-in microphone (the mouthpiece) into a live
microphone for a Bluetooth speaker. Speak into the phone and your voice comes out of the speaker.

## How it works

- Records from the phone's **built-in mic** using the plain `MIC` source. The app avoids the
  call-style source, so Android doesn't switch the speaker into low-quality "phone call" mode.
- Plays each 5 ms chunk straight to the **Bluetooth speaker** through a low-latency `AudioTrack`.
  Bluetooth itself adds about 0.15–0.25 s of delay, and no app can remove that.
- Runs as a **foreground service**, so it keeps going with the screen off. A notification
  with a Stop button shows while the mic is on.
- Includes a volume boost (50–400%), a level meter, optional noise reduction, and a
  warning when no Bluetooth speaker is connected (the phone speaker would squeal).

Requires Android 8.0 or newer.

## Get the APK

**Option A: Android Studio**
1. Install [Android Studio](https://developer.android.com/studio).
2. Choose *Open* and select this `LiveMic` folder. Let Gradle sync finish.
3. Plug in your phone with USB debugging on and press **Run**, or use
   *Build → Build App Bundle(s) / APK(s) → Build APK(s)* and copy
   `app/build/outputs/apk/debug/app-debug.apk` to your phone.

**Option B: GitHub (no installs)**
1. Push this folder to a GitHub repository.
2. The **Build APK** workflow runs automatically and publishes the APK as a release.
3. On your phone, open `https://github.com/<you>/<repo>/releases/latest/download/LiveMic.apk`
   and install it. Allow "install unknown apps" when asked.

## Using it

1. Pair and connect your Bluetooth speaker in the phone's Bluetooth settings.
2. Open Live Mic and tap **START**. Allow microphone access the first time.
3. Speak into the bottom of the phone. If the speaker squeals, move away from it or lower the boost.
