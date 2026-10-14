# TerminalSession usable in app tests, and CoroutineScope

- Status: Withdrawn (2026-10-14, replaced by [0026](0026-session-module.md) in a discussion with a human. 0026 carries over "no separate headless type" from decision 1 and the motivation of using the same type as production in app tests. Decisions 2–6 were reversed by 0026)
- Date: 2026-10-14
- Related: [0007](0007-compose-session.md) (owning the session), [0021](0021-thread-checks.md) (checking the calling thread), [0024](0024-thread-conventions.md) (thread conventions), requirements 4.2 and 6.5

## Background

Creating a public API for using the terminal without Compose (a headless API) was discussed with a human. At first, the option considered was to put `HeadlessTerminal` in a JVM module combining vtfx-core and vtfx-gate (`vtfx-headless`), and build `TerminalSession` on top of it.

During the discussion, it became clear that the main use of headless is unit tests of terminal apps (in JUnit, using vtfx as a real dependency). What app tests want to check is the app's production code, and that code receives a `TerminalSession`. Even if a separate type were published, tests could not pass it to the production code. Making it passable would bring the concept of headless into production code. What was needed was for the same `TerminalSession` as production to work in tests.

The only Android APIs `TerminalSession` depends on are `Handler` and `Looper`, used for checking for the main thread, forwarding work to the main thread, and two timers (batching resizes over 150 ms, waiting up to 200 ms for synchronized output). In JUnit without Robolectric, `Looper.getMainLooper()` in the constructor throws a "not mocked" exception, so it cannot even be created.

## Decision

1. **No separate headless type or module.** App tests can use the same `TerminalSession` as production in JUnit without Robolectric.
2. **`TerminalSession` takes a `CoroutineScope` as a required argument.** No default value (it is not implicitly fixed to `Dispatchers.Main`). The scope's dispatcher determines the session's thread, and its `Job` the session's lifetime. The three uses of `Handler` are replaced with the scope, removing `TerminalSession`'s dependency on `android.os`. In production the app passes `viewModelScope` or the like (`Dispatchers.Main.immediate`), and in tests it passes `runTest`'s `backgroundScope` or the like and advances the timers in virtual time.
3. **Do not require `Dispatchers.setMain()` for tests.** It changes behavior too much from production. By passing the scope explicitly, control of the test is confined to the scope the test passes.
4. **The session's thread is the thread that constructed the session.** 0021's "main-thread-only" is read as "session-thread-only". Both the entries of the dedicated operations and the work run in the scope check that the current thread is the constructing thread, and throw `IllegalStateException` if not. Passing a wrong dispatcher (`Dispatchers.Default` and so on) fails deterministically on the first work run in the scope. 0021's "the constructor may be called from any thread" is revised.
5. **The scope of the requirement is Android and JVM tests.** Other platforms stay out of scope as in requirement 4.2, and the requirements are not changed. Room for using them is left in the internal structure, and if needed, the UI-independent parts are moved to a JVM module and published.
6. **Tests to prevent regressions**: Put a JUnit test without Robolectric in vtfx-compose that creates a `TerminalSession` and runs `feed`, sending, and the timers. Keep it working in the same environment as an app's local unit tests (AGP's "not mocked" `android.jar`).

### Results of experiments (2026-10-14)

The following was confirmed with vtfx-compose's local unit tests (no Robolectric runner, AGP's mock `android.jar`, Compose 1.12.1, kotlinx-coroutines 1.9.0). The experiment's code was not committed.

- Reading and writing Compose's `mutableStateOf` and `mutableIntStateOf`, and `Snapshot.sendApplyNotifications()`, work.
- `Color.toArgb()` (the colors of `TerminalColors.Dark`) works.
- With kotlinx-coroutines-test's `runTest`, work launched in `backgroundScope` runs on the test thread. `delay(150)` advances deterministically with `advanceTimeBy`. Work launched from another thread also runs on the test thread.
- The current `TerminalSession` throws `RuntimeException: Method getMainLooper in android.os.Looper not mocked` in the constructor.

This has not been tried in an app's own module. The mechanism is the same (AGP's mock `android.jar`), so the result is expected to be the same.

## Alternatives considered

- **Put `HeadlessTerminal` in a JVM module and publish it**: As in the background, app tests could not pass it to production code. Making it passable would bring the concept of headless into production code.
- **Fix it to `Dispatchers.Main` and replace it with `Dispatchers.setMain()` in tests**: A method Android developers often use in ViewModel tests, but it rewrites the global main dispatcher only during tests, so the difference in behavior from production is large (a human's decision).
- **Have apps use Robolectric**: It works as is and only needs documentation. However, startup is heavy, and the Android runtime has to be downloaded. It would be a burden on app tests.
- **Create a session whose main thread and clock can be controlled, in a test artifact (`vtfx-testing`)**: The published artifacts and APIs would increase. With the shape of passing a scope, the existing tool kotlinx-coroutines-test is enough.
- **Pass a `CoroutineContext` or `CoroutineDispatcher`**: It can represent the thread but not the lifetime. A `CoroutineScope` represents both in one, the same convention as `stateIn(scope)` and the like.
- **Abstract `Handler` into our own interface (a scheduler)**: A vtfx-specific test implementation would need to be written by the app or provided by vtfx. With coroutines, existing tools are enough.
- **Make the session's thread the thread that first calls a dedicated operation**: 0021 would not need to change, but if the first call is on the wrong thread, the later correct calls fail instead, and the location of the mistake is hard to find.
- **Judge whether on the session's thread with the dispatcher's `isDispatchNeeded`**: Accurate with `Dispatchers.Main.immediate`, but `StandardTestDispatcher` always returns true, so correct calls fail in tests.

## Impact of reversing

- Remove the scope from the constructor of `TerminalSession` and go back to `Handler`. App tests go back to needing Robolectric.
- Revise the JUnit test without Robolectric and the app testing section of usage.md.
- If the way the session's thread is determined changes, revise the compose rows of 0021 and 0024.
