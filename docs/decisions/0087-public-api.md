# The public API: scope, compatibility, and extensibility

- Status: Approved
- Date: 2026-10-25
- Related: Requirements 4.1, 4.2, 6.1, and 6.5, and section 8 of [REQUIREMENTS.md](../REQUIREMENTS.md); sections 2 and 7 of [architecture.md](../design/architecture.md); [usage.md](../usage.md); sections 5, 6, 7.2, and 13 of [compose-input.md](../design/compose-input.md); section 9 of [testing.md](../design/testing.md); section 11 of [fonts.md](../design/fonts.md); [0018](0018-distribution.md), [0029](0029-import-without-gradle.md), [0083](0083-gate.md), [0074](0074-api-reference-of-the-default-branch.md)

## Background

`vtfx-compose` exposes the other library modules with `api`, because apps use their types (`HostConnection`, `KeyStroke`, `TerminalSnapshot`, and so on; section 2 of [architecture.md](../design/architecture.md)). Declarations used only between modules are then visible to apps too, and Kotlin's `internal` cannot cross modules, so they have to be `public`.

Requirement 6.5 asks for keeping the public API to the minimum needed, mechanically detecting changes to it (including binary compatibility), defining the compatibility policy and when to start guaranteeing stability, and documenting the contract of the public API.

In xterm.js, addons (automatic links, search, shell integration, saving sessions, and so on) change the terminal's behavior. The addon mechanism itself is thin: an addon receives a `Terminal` and uses the public API (`registerLinkProvider`, `parser.registerOscHandler`, `registerMarker`, `registerDecoration`, `buffer`, and so on), so whether vtfx can accommodate these features depends on which basic APIs it exposes.

## 1. Scope of the public API

1. The public API is the `public` declarations of the nine library modules (section 2 of [architecture.md](../design/architecture.md)) that are not marked `@InternalVtfxApi`.
2. `@InternalVtfxApi` (`vtfx-core`) is an opt-in marker at error level. The vtfx modules opt in, in each file that uses a marked declaration ([0029](0029-import-without-gradle.md)); apps must not (section 1 of [usage.md](../usage.md)). The sample app compiles without opting in, which confirms that vtfx can be embedded with the public API alone.
3. Marked are the declarations shared only between modules, such as `Terminal`, `ImeEditor`, `TerminalGate`, and `HostWriter` (sections 2 and 3.3 of [architecture.md](../design/architecture.md)); the current set is what the sources mark. Nested declarations are marked too, because the compatibility check does not exclude nested classes based only on the marker of the outer class.
4. `TerminalSnapshot` and its parts (`LineSnapshot`, `CellStyle`, `TerminalColor`, `TerminalModes`, and so on) are in the public API, as the result of `TerminalSession.snapshot()`.

**Why.** The marker keeps the declarations that the modules share `public`, as Kotlin requires across modules, while keeping them out of the public API and its compatibility policy (topic 2), as requirement 6.5 asks.

**Alternatives considered.**
- **Merge into one module and use `internal`**: It would break the design that checks the security boundary (only the gate can send to the host) mechanically, by modules (section 5 of [architecture.md](../design/architecture.md)).
- **Allow `internal` between modules with `-Xfriend-paths`**: An unofficial compiler setting, and its handling across Gradle projects is not guaranteed.
- **Make `vtfx-compose` depend with `implementation` and rewrap only the needed types in compose**: Types such as `KeyStroke` and `TerminalSnapshot` would be duplicated in compose, increasing conversion effort and the risk of inconsistencies.

**Impact of reversing.** To change the scope, add or remove `@InternalVtfxApi` and run `updateKotlinAbi`.

## 2. Compatibility policy

1. Versions follow Semantic Versioning.
2. Before 1.0 (0.x), compatibility is not guaranteed. Changes to the public API are still always detected by the check (topic 5) and appear in commits as differences in the dump; for a change that breaks compatibility, the commit message gives the reason.
3. From 1.0 on, changes that break compatibility are made only in major versions. An API to be removed is first marked `@Deprecated` (WARNING) pointing to its replacement, becomes ERROR after at least one minor version, and is removed in the next major version. A parameter added from 1.0 on keeps the previous signature (topic 4).
4. `@InternalVtfxApi` declarations may change without notice in any version.
5. When to start guaranteeing stability (1.0) is decided by humans once the publishing (Maven) policy is decided ("Publishing" in [review-queue.md](../review-queue.md)).

**Why.** Requirement 6.5 asks for the versioning, the deprecation procedure, and when to start guaranteeing stability. Before 1.0, the parts of the API whose likely changes would break apps compiled against an earlier version can still be changed cheaply (topic 3), and the check keeps every change visible in review.

## 3. The shape of the public API before 1.0

1. **No data classes among what vtfx returns.** `TerminalModes`, `CursorState`, `Hyperlink`, the kinds of `TerminalLink` (`Explicit` and `Matched`), and `TerminalLinkClick` are ordinary classes with internal constructors and hand-written `equals`, `hashCode`, and `toString`, like `TerminalSnapshot` (for the links, section 6.1 of [compose-input.md](../design/compose-input.md)). Apps read their properties, which can grow compatibly; they neither build nor destructure them. The tests of other modules make `TerminalModes` and `Hyperlink` through functions of vtfx-core's test fixtures, which are compiled as a friend of vtfx-core.
2. **No public `const val`s.** Every public constant is a plain `val`, read through its getter at run time, so that a later version can change the value: `TerminalLimits.MAX_DIMENSION`, `MAX_SCROLLBACK_LINES`, and `MAX_STRING_LENGTH`, `CharWidth.UNICODE_VERSION`, `TerminalSession.DEFAULT_COLUMNS` and `DEFAULT_ROWS`, and `GatePolicy.DEFAULT_MAX_CLIPBOARD_WRITE_LENGTH`. Constants that stay inside one module may still be `const val`s; vtfx-compose reads `MAX_DIMENSION` of vtfx-core through the getter like apps, since the two artifacts can be of different versions. The signature dump fails on a public `const val` (topic 5).
3. **Enums and sealed types are decided per type.**
   - The closed types, which take a new entry or subtype only in a major version, are `UnderlineStyle`, `CursorShape`, and `OpenUrlResult` with its subtype `UrlOpenerResult` ([0083](0083-gate.md)).
   - The other enums and sealed types that vtfx returns are shaped so that apps cannot match them exhaustively. `TerminalLink` is an abstract class with an internal constructor, so that apps write `else` and new kinds of links can be added in a minor version, while only vtfx makes links.
   - `MouseTracking` and `MouseEncoding`, and the properties of `TerminalModes` that hold them, are not public API (marked `@InternalVtfxApi`). If apps need it, the public API tells only whether the host asks for the mouse, as a `Boolean`; no app needs it yet.
   - The types that apps give to vtfx (`TerminalKey`, `KeyStroke`, `TerminalFont`, `AmbiguousWidth`) may take new entries and subtypes in a minor version.

**Why.**
- **Data classes**: A new property (a new mode; the position of a link click, which [0085](0085-compose-interaction.md) foresees) changes the signatures of the constructor and `copy`. Hidden overloads (topic 4) can keep both, but `componentN` still fixes the order of the properties, `equals` and `toString` cover every property, and apps can build any state. An `internal` constructor alone leaves `copy` public, which Kotlin 2.4 warns about (an error with `allWarningsAsErrors`).
- **Constants**: The values of public `const val`s are copied into apps at compile time, and the ABI check does not see a change of value. The `TerminalLimits` bounds bound arguments that vtfx checks with `require`, so lowering one would make apps compiled earlier throw `IllegalArgumentException`. `UNICODE_VERSION` would report the version that an app was compiled with, not that of the tables it runs with, and the defaults would show stale values. A single rule is simpler than deciding for each constant whether its value may change, and a check can enforce it.
- **Enums and sealed types**: An exhaustive `when` compiled earlier throws `NoWhenBranchMatchedException` on an entry or subtype added later. Kotlin has no marker for a non-exhaustive type, like Rust's `#[non_exhaustive]` or Swift's `@unknown default`; libraries either treat a type as closed and add to it only in a major version (Compose's `LayoutDirection`), make it a value class with constants in its companion, on which `when` needs `else` (Compose's `PointerType` and `TextAlign`), or give it an entry for unknown values. The types that apps give to vtfx can grow without breaking apps. Of those that vtfx returns, `TerminalLink` (new kinds of links) and `MouseEncoding` (SGR-Pixels, mode 1016) are likely to grow, while `UnderlineStyle` (SGR 4:0 to 4:5), `CursorShape` (DECSCUSR), and `MouseTracking` are nearly closed. Only vtfx-compose reads the mouse modes of `TerminalModes`; neither the sample app nor [usage.md](../usage.md) does. Why `OpenUrlResult` is closed is in [0083](0083-gate.md).

**Alternatives considered.**
- **Keep the data classes, and keep the previous constructor and `copy` with hidden overloads (topic 4)**: Fewer changes, but the order of `componentN`, the properties in `equals` and `toString`, and the public construction stay fixed.
- **Data classes with internal constructors and `@ConsistentCopyVisibility`**: Hides construction and `copy`, but leaves `componentN` and `equals` public.
- **Plain `val`s only for the values that may change (the bounds and `UNICODE_VERSION`)**: Keeps the defaults as constants, but needs a judgment for each constant, which a check cannot make.
- **Keep the `const val`s, and promise that the bounds only rise and that `UNICODE_VERSION` is the version at compile time**: No change, but it relies on every later change keeping the promise, and the ABI check does not see a change of value.
- **Treat every enum and sealed type as closed**: Simple, but `TerminalLink` and `MouseEncoding` would likely need major versions.
- **Allow additions in minor versions and ask apps in the documentation to write `else`**: Nothing enforces it, and apps that miss it crash.

**Impact of reversing.**
- Data classes: turn the classes back into data classes; their constructors and `copy` then follow topic 4.
- Constants: turn the `val`s back into `const val`s, and decide again how a change of value is reviewed, since the ABI check does not see it.
- Enums and sealed types: make `TerminalLink` sealed again, or make the closed types open; return `MouseTracking` and `MouseEncoding` to the public API.

## 4. Adding parameters after 1.0

1. Before 1.0, parameters may be added without keeping the previous signature (topic 2): the difference in `api/` and the reason in the commit message are enough.
2. From 1.0, when a parameter is added to a public function, constructor, composable, or `copy`, the previous signature is kept as an overload marked `@Deprecated("...", level = DeprecationLevel.HIDDEN)` that delegates to the new one.
   - The overload keeps the default values of the previous signature: callers that left a parameter to its default call the synthetic default method of the previous signature, which exists only if the overload has the same defaults.
   - The new parameter has a default value, so that sources calling the function keep compiling.
   - `HIDDEN` makes the overload synthetic: sources cannot call it, and the dump shows it as `public synthetic fun`. Reviewers check that a difference in `api/` that adds a parameter keeps the previous lines, as synthetic.
   - The hidden overloads are removed in the next major version, with the other deprecated APIs.
3. The ABI check catches a forgotten overload: the lines of the previous signature disappear from the dump, and `checkKotlinAbi` fails.
4. Kotlin's `@IntroducedAt` is not used while it is experimental. When it becomes stable, it may replace the hand-written overloads: the overloads it generates have the same descriptors as the hand-written ones (the dump differs only in the synthetic flag, which does not affect linking), so switching does not break apps.
5. Whether a new setting becomes a parameter or a property of a settings class (like `TerminalStyle`) is decided case by case; either way, the overloads above keep the previous signatures.

**Why.** In Kotlin, a new parameter changes the JVM signature of a function or constructor even when it has a default value, and so does the synthetic method that applies the defaults (`$default`, or the constructor taking a bit mask and `DefaultConstructorMarker`; composables take the mask in the same method). An app compiled against the previous version then fails with `NoSuchMethodError` unless it is recompiled. The parts of the public API most likely to grow are the parameters of `Terminal` and of the `TerminalSession` constructor, and the properties of `TerminalStyle`, `TerminalColors`, `GatePolicy`, and `TerminalLimits` (their constructors, and `copy` where there is one); the deprecation procedure covers removing an API, not adding a parameter. A hand-written hidden overload is how AndroidX and Compose libraries add parameters. Both it and `@IntroducedAt` (Kotlin 2.3 and later, experimental, needing `@OptIn(ExperimentalVersionOverloading::class)`, an opt-in at error level) keep apps compiled against the previous version running. `@IntroducedAt` needs no compiler flag and also covers composables and a data class's constructor and `copy`, but it rejects default values of earlier parameters that are too complex for the generated overloads (`VERSION_OVERLOADS_TOO_COMPLEX_EXPRESSION`), such as `object : TerminalSessionListener {}` in the constructor of `TerminalSession`.

**Alternatives considered.**
- **`@IntroducedAt` now**: Less code for each new parameter, but it is experimental, so its name or meaning may change, and other build systems that compile the sources ([0029](0029-import-without-gradle.md)) would need Kotlin 2.3 or later and the opt-in. It also rejects some default values that the current API has.
- **`@JvmOverloads`**: Generates one overload for each prefix of the parameters, which includes the previous full signature, but the synthetic default method of the previous signature, which Kotlin callers that use default arguments call, still changes. Its overloads are meant for Java, which is out of scope (requirement 4.2).
- **Major versions for every new parameter**: Simple, but parameters are the most common kind of addition, so major versions would come often.
- **A rule to put settings that are likely to grow into a settings class rather than into the parameters of `Terminal` or `TerminalSession`**: Fewer places would need overloads, but whether a setting belongs to a parameter or a settings class depends on the setting.
- **Settings with mutable properties or builders**: Adding a property is then compatible without overloads, but it departs from the immutable settings of Compose and of the existing classes (`TerminalStyle`, `GatePolicy`).

**Impact of reversing.**
- To adopt `@IntroducedAt`, replace each hidden overload with the annotation on the parameters added after it, and check that the dump keeps the previous descriptors.
- To stop keeping previous signatures, remove this procedure, and bump the major version for each new parameter (topic 2).

## 5. The compatibility check

`check` compares each library module's committed `api/` with the current code and fails on a difference (section 7 of [architecture.md](../design/architecture.md), section 9 of [testing.md](../design/testing.md)). After an intended change, `./gradlew updateKotlinAbi` updates the dumps, and the diff is reviewed and committed.

1. The ABI of each library module is dumped into `<module>/api/<module>.api`. JVM modules use the Kotlin Gradle Plugin's ABI validation (`abiValidation`, an experimental API), which leaves out `@InternalVtfxApi` declarations. For the Android modules (`vtfx-compose`, `vtfx-gate-android`, `vtfx-font-gate-android`), `DumpAbiTask` in `build-logic` dumps the release Kotlin classes with the ABI feature of the Kotlin Build Tools API, which KGP uses internally, in the same format and with the same task names (`updateKotlinAbi`, `checkKotlinAbi`). That dump does not leave out `@InternalVtfxApi` declarations, because the filters that the task passes to the Build Tools API have no effect (section 11 of [fonts.md](../design/fonts.md)); of the Android modules, only the signature dump (item 2) holds just the public API. The implementation of the Build Tools API (`org.jetbrains.kotlin:kotlin-build-tools-impl`) is pinned to the same version as Kotlin; it is the same artifact KGP uses for compilation.
2. Each library module also dumps the Kotlin signatures of its public API into `<module>/api/<module>.signatures`, read from the Kotlin metadata of the compiled classes (`KotlinSignatures` in `build-logic`). A parameter with a default value shows as `= ...` (the value is in the function's body, so it can change), and a hidden overload as `@Deprecated(HIDDEN)`. `updateKotlinAbi` writes it, and `checkKotlinAbi` and `check` compare it. It fails on a public `const val` (topic 3).
3. Neither dump sees changes to the body of public `inline` functions (there are none), Compose's stability inference, or the Kotlin version that apps need. The modules are compiled without `languageVersion` or `apiVersion`, so upgrading Kotlin raises the Kotlin compiler that an app needs to read vtfx's metadata, with no difference in `api/`. The methods with default bodies of `TerminalSessionListener` are JVM default methods (the default `-jvm-default` mode of Kotlin 2.2 and later), so a Kotlin compiler older than 2.2 that compiles the sources ([0029](0029-import-without-gradle.md)) gives them another ABI.

**Why.** Requirement 6.5 asks for detecting changes to the public API, including binary compatibility, mechanically. With AGP 9's built-in Kotlin, KGP's ABI validation cannot find the compilation of the Android modules and cannot produce a dump. The ABI dump holds JVM descriptors only, which miss changes that break apps: removing a parameter's default value, the value of a `const val`, type arguments, nullability, parameter names, `sealed`, and `fun interface`; the signature dump covers them.

**Alternatives considered.**
- **kotlinx binary-compatibility-validator**: It is being replaced by KGP's ABI validation, and its Android support with AGP 9's built-in Kotlin could not be confirmed.
- **Stop using AGP's built-in Kotlin in the Android modules and go back to the kotlin-android plugin**: It would change how all Android modules compile just for ABI validation, and future versions of AGP will not offer the choice.
- **metalava**: The standard for checking Android public APIs, but its dependencies and build configuration are large.

**Impact of reversing.** Once KGP supports AGP's built-in Kotlin, drop `DumpAbiTask` and `CheckAbiTask` in `vtfx.android-library` and use the same `abiValidation` as the JVM modules. When upgrading KGP or the Build Tools API, if the dump format changes, update the dumps with `updateKotlinAbi`.

## 6. The API reference

1. The HTML API reference of the library modules is generated with Dokka. Each module applies `vtfx.dokka` from `build-logic`, and `vtfx.api-reference` at the root aggregates them (`./gradlew :dokkaGenerate`, output in `build/dokka/html`). Generation is not part of `check`; it is for publishing. Where its source links point and how it is published are in [0074](0074-api-reference-of-the-default-branch.md).
2. The footer of the reference carries the same notice as the license headers of the sources, and the version comes from `gradle.properties`.
3. `@InternalVtfxApi` declarations are excluded from the reference with KDoc's `@suppress`, and `scripts/check_kdoc.py` checks for missing ones (section 9 of [testing.md](../design/testing.md)).
4. `scripts/check_api_reference.py` checks the generated reference for broken links, and `scripts/check_kdoc.py` checks that each property declared in a primary constructor has its own KDoc or `@property`. The rules are in the docstrings of the two scripts.

**Why.** Dokka has no setting to exclude declarations by annotation. It renders links that it resolves to pages it does not generate as broken links without a warning (links to `@suppress`ed declarations, and unqualified links to properties declared in a primary constructor), and it shows neither mentions in the class description nor `@param` as the description of a property declared in the primary constructor.

**Alternatives considered.**
- **Keep internal declarations in the reference and show the marker in the signature with `@MustBeDocumented`**: No maintenance needed, but internal declarations would be mixed into the reference.
- **A Dokka plugin of our own that excludes by annotation**: More modules, and Dokka's plugin API is not stable.

## 7. What the KDoc in the reference says

1. KDoc that appears in the reference (public declarations not excluded with `@suppress`) does not refer to documents or files in the repository; the contract is complete within the KDoc. `scripts/check_kdoc.py` checks that `docs/`, `scripts/`, and `requirement <number>` do not appear in it (section 9 of [testing.md](../design/testing.md)).
2. References for maintainers (decisions, requirement sections, design documents) go in line comments (`//`) before the declaration or in comments in the implementation, and are kept only where the decision something is based on is not obvious.
3. Implementation details (data representation, generation steps) are written in comments, not KDoc.
4. The KDoc of `@InternalVtfxApi`, `internal`, and `private` declarations does not appear in the reference, so it may refer to the repository.
5. Dokka 2.2 shows a nullable function type such as `onLinkClick: ((TerminalLinkClick) -> Unit)?` of `Terminal` without the parentheses, as `(TerminalLinkClick) -> Unit?`. This is accepted as a limitation of Dokka: the API is not changed to work around it (a type alias would add to the public API), and the KDoc of such a parameter says in words that it may be null.

**Why.** The readers of the reference are developers of apps embedding vtfx, who do not necessarily read this repository's documents. In the reference, references to repository documents and files do not become links, and point to documents readers cannot open. Implementation details, such as the bit layout of `CellStyle` or the script that generates the table of `CharWidth`, are invisible to apps.

**Alternatives considered.**
- **Make references links to GitHub URLs**: A reference of one version would point to the latest documents on `main`.
- **Keep the references and explain at the top of the reference that they point to documents in the repository**: Fewer changes, but it would still point to documents readers cannot open.
- **Just delete the references**: Maintainers could no longer trace from the code to the decisions.

**Impact of reversing.** Put the references back into the KDoc and remove the check in `check_kdoc.py`. Only the presentation of the reference changes; there is no impact on the API.

## 8. Extensibility

1. No mechanism for registering addons (a mutable registry with a lifecycle of registration, activation, and disposal). In Compose, passing values declaratively as arguments of `Terminal` or at construction of `TerminalSession` plays the same role.
2. No mechanism for inserting app code into the interpretation of host output (a synchronous handler like xterm.js's `registerOscHandler`).
3. Features with no room for the app to customize are built in, and options are not added until requested. Public APIs are provided only for features involving the app's UI or app-specific meaning, and each is designed carefully one by one and recorded in a decision.
4. The use cases of xterm.js's addons are handled as follows.

| Use case | How xterm.js does it | How vtfx handles it |
|---|---|---|
| OSC 52 clipboard | ClipboardAddon | Built in (`ClipboardAccess`, `GatePolicy`; section 4.1 of [gate-policy.md](../design/gate-policy.md)) |
| Following the display size | FitAddon | Built in (`Terminal`; section 3 of [session-and-threads.md](../design/session-and-threads.md)) |
| Unicode widths and graphemes | Unicode11, UnicodeGraphemes | Built in (section 7 of [emulation.md](../design/emulation.md)) |
| Connection with the host | AttachAddon | Built in (`HostConnection`, `feed`) |
| Rendering method | WebGL, Canvas | Not needed ([0014](0014-performance.md)) |
| Automatic URL links | WebLinksAddon, link provider | Built in; the app can also give text patterns and rules for converting them to URLs (topic 9) |
| Link hover | link decorations, hover | Built in, for the mouse: the pointer icon changes over a link (section 3 of [compose-input.md](../design/compose-input.md)) |
| Key interception by the app | `attachCustomKeyEventHandler` | A per-terminal means, `onPreImeKeyEvent` of `Terminal` (section 7.2 of [compose-input.md](../design/compose-input.md), [0085](0085-compose-interaction.md)) |
| Search and highlighting | SearchAddon, decoration | Matching, highlighting, and scrolling to a match are built in; the search field belongs to the app, which drives the search through an API (section 13 of [compose-input.md](../design/compose-input.md), [0085](0085-compose-interaction.md)) |
| Confirmation before pasting | The app's implementation | Like hyperlinks, a shape that notifies the app and lets it confirm is to be considered. Clipboard suggestions of the IME arrive through `commitText`, so not every path can be covered; those of several lines are sent as pastes, bracketed if the host enabled bracketed paste ([0082](0082-ime-editing-model.md)) |
| Progress (OSC 9;4) | ProgressAddon | If needed, exposed as typed state like `title` |
| Shell integration (OSC 633, 133, 7) | OSC handlers, markers, decorations | Not supported |
| Saving and restoring sessions | SerializeAddon | Not supported. For multiple tabs, it is enough for the app to have a `TerminalSession` per tab ([0084](0084-session-and-threads.md)). When the process ends, the connection to the host is cut too, and if the connection is kept in a service, the session lives there too |
| Link actions decided by the app (opening paths and so on), reading the selected text, items of the long-press menu | link provider, `getSelection`, and so on | Not made until a use appears. For the app's own Copy command, `TerminalState` reads whether there is a selection and copies, selects all, or clears it (section 5 of [compose-input.md](../design/compose-input.md), [0085](0085-compose-interaction.md)) |
| Typeahead (VS Code's typeahead) | An addon that depends on internal APIs | Deferred because of its size |
| Images, ligatures | ImageAddon, LigaturesAddon | Ligatures are out of scope (requirement 4.2). Images: the kitty graphics protocol is built in (section 9 of [emulation.md](../design/emulation.md)) |

**Why.** An addon registry is too flexible, and makes it hard to keep the scope of the public API (requirement 6.5) and the security boundary (section 1 of [architecture.md](../design/architecture.md)). A synchronous handler of host output would run app code on the `feed` thread while holding the lock, and could take over the default handling of OSC 52 and OSC 8 and bypass the gate's policies.

**Alternatives considered.**
- **The same addon mechanism as xterm.js**: As above, it makes it hard to keep the public surface and the security boundary. Even in xterm.js, some of VS Code's addons depend on internal APIs: demand that the design of the public API does not meet leaks outside the mechanism.
- **Notify unknown OSCs as events, only for numbers the app allows**: It avoids the problems of synchronous handlers, but it would not support shell integration, which was its main use, so it is not made. Progress (OSC 9;4) would be built in as typed state if needed.

**Impact of reversing.**
- Supporting shell integration needs tracking lines across eviction from the scrollback and reflow, the problem that [0085](0085-compose-interaction.md) avoided for selection; start by considering giving `Line` a prompt marker.
- Supporting saving and restoring sessions needs an API that reads the buffer, including the scrollback. It shares its foundation with the search API.

## 9. Link patterns

The app can add links by giving pairs of a regular expression and a URL template (e.g. `crbug/123` to `https://crbug.com/123`). The sections below are those of [compose-input.md](../design/compose-input.md), which has the API and the rules; "Links" in [usage.md](../usage.md) tells apps how to use them.

1. **The conversion is a template, not a function**: vtfx percent-encodes the values it puts into the template (section 6.2). A value of `.` or `..` still makes a dot segment of the path, which opening the URL removes; this is accepted.
2. **Links say where they come from**: an OSC 8 hyperlink, or text that a pattern matched, built-in URLs included (section 6.1). Every kind is opened through `TerminalSession.openUrl`, where the gate checks the URL (section 5 of [gate-policy.md](../design/gate-policy.md)).
3. **Precedence errs on the side of fewer links**: an OSC 8 hyperlink wins, overlaps between patterns are decided from the text alone, and the matches that a dropped match overlapped are not restored (section 6.3).
4. **A match over concealed text (SGR 8) is not a link**, like a match that touches OSC 8; it is dropped rather than cut at the concealed cell (section 6.3).
5. **No limit on the effort of matching**: giving patterns whose effort is proportional to the length of the text is the app's responsibility, as the KDoc of `LinkPattern` and [usage.md](../usage.md) say. Only lines whose content changed are matched again (section 6.3).
6. **Logical lines are matched whole**, with the rows of a cut line outside the display (section 6.3).

**Why.**
- **Template**: The matched text, which the host can decide, cannot change the structure of the URL (its host or its path). Encoding cannot prevent dot segments, since the WHATWG URL specification also treats `%2E` as a dot segment, but the host can neither change the host being opened nor add segments, which was judged acceptable.
- **Origin**: The display and the destination of an OSC 8 hyperlink can disagree, while a detected URL is its own destination. Text the host draws in the background's color cannot be told from the cells, so the KDoc of `Terminal` and `TerminalLink` and [usage.md](../usage.md) ask apps to show the URL and confirm for every link, not only OSC 8 hyperlinks.
- **Precedence**: It avoids part of one displayed URL becoming a link to a different destination through a later pattern, and does not enable spoofing.
- **Concealed text**: The renderer does not draw concealed text, so such a link would go to a URL the user did not see (`https://good.example` shown, `https://good.example.evil.example` opened).
- **No limit**: The app's regular expressions run against text the host decides, on the main thread, and with nested or overlapping repetition (`(a|aa)+b` and so on) the host can make matching take exponentially long with a few dozen characters. Android's `java.util.regex` passes the text to ICU's native engine and exposes no means of stopping matching midway, so a limit that vtfx set would not work on Android.
- **Logical lines**: Matching only the display made part of a logical line look like the start of a word (the second half of `xhttps://`) or cut a match midway (`crbug/1|234` while showing the scrollback), producing links different from those of the whole logical line.

**Alternatives considered.**
- **Take the link conversion as a function**: More flexible, but encoding the matched text becomes the app's responsibility.
- **Bound the effort of matching**: Counting the reads of the text, or catching `StackOverflowError`, assumes HotSpot's `java.util.regex` and does not work on ART. Matching with an engine that guarantees linear time (RE2/J) would add an external dependency, lose back-references and lookahead, and change the `LinkPattern` API. Matching on another thread cannot stop matching, so it would keep using CPU, and matching would pile up while output continues.

**Impact of reversing.** Changing the link rules changes `LinkFinder` and `LinkPattern` in `vtfx-core` and section 6 of [compose-input.md](../design/compose-input.md).
