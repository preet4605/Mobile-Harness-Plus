# Frame-timing benchmark

Test-only Macrobenchmark module (`:benchmark`) that measures frame timing where the glass and
motion work costs the most. It never ships in the app.

| Script | What it measures |
|---|---|
| `scrollProjects` | Flinging the Projects list under the glass bars and tab bar |
| `openAndCloseProject` | The push transition into a project and back, with the title morph |
| `scrollChat` | Flinging a chat under the composer and tab bar |

Budget: no missed frames at the panel's refresh rate, read as `frameOverrunMs` P90 ≤ 0
(8.3 ms frames at 120 Hz).

## Running it on a phone

1. Connect the phone with USB debugging on.
2. Install the benchmark build once and set it up by hand (it has its own app id,
   `com.jarves.mh.bench`, so the real app and its data are never touched):
   `./gradlew :app:installOnlineBenchmark`, open "Mobile Harness (Benchmark)", finish setup,
   and create a project with a short chat.
3. Run:
   `./gradlew :benchmark:connectedBenchmarkAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true`
   The flag keeps the benchmark app (and the setup from step 2) installed after the run.
4. Results: `benchmark/build/outputs/connected_android_test_additional_output/`
   (JSON plus Perfetto traces).

## Without the benchmark

With the normal debug build installed:

    adb shell dumpsys gfxinfo com.jarves.mh.dev reset
    # scroll Projects, open a project, scroll the chat
    adb shell dumpsys gfxinfo com.jarves.mh.dev

"Janky frames" and the 90th/95th percentile lines are the numbers to compare.

## Glass kill switches

`LiquidGlassConfig` has a flag per glass pass (`enableRefraction`, `dispersion`,
`adaptiveWash`, `edgeLight`, `pressGlow`, `mergeShapes`, `progressiveEdge`) and the capture
resolution (`scaleFactor`). Turn passes off one at a time to find what costs frames on a
given phone.
