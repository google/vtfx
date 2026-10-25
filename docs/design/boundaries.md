# Module boundaries and their check

Status: Living document. It describes the current design and is kept in sync with the implementation; design choices and their reasons are recorded in [decisions/](../decisions/README.md).

This document describes how the build checks the module boundaries declared in `config/boundaries.toml`: which modules may depend on which, what the compiled classes of each module may refer to, and what a library's artifact may bring into apps. The modules themselves and why the boundaries are drawn where they are are in [architecture.md](architecture.md); the reasons for checking them this way are in [decisions/0044](../decisions/0044-boundaries-in-gradle-and-classes.md). The check is implemented by the convention plugin `vtfx.boundaries` and the files it uses in `build-logic/src/main/kotlin/` (`Boundaries.kt`, `BoundaryTasks.kt`, `ClassReferences.kt`, `ClassHierarchy.kt`, `LibraryArtifacts.kt`).

## 1. Overview

- The rules are all in `config/boundaries.toml` (section 2). The check reads Gradle's model of the build, the compiled classes, and the published artifacts, not the source text, so that it holds against changes written to get around it, within the limits of section 7 ([decisions/0044](../decisions/0044-boundaries-in-gradle-and-classes.md)).
- The root project applies `vtfx.boundaries`, and the plugin registers the tasks on every other project itself, so that a module cannot leave the check out by not applying a convention plugin. Once all projects are evaluated, the build fails if a project applies neither the `java` plugin nor an Android plugin (`com.android.library`, `com.android.application`), since its classes could not be read.
- The tasks:

| Task | Project | Checks |
|---|---|---|
| `checkBoundaries` | root | The projects of the build against the modules of the rules (section 3). Depends on all the tasks below, fails if any of them did not pass in the same build, and runs with the root project's `check`, so with `./gradlew build` in `scripts/check.sh`. |
| `checkDependencies` | each module | The dependencies between modules (section 3). |
| `checkClassReferences` | each JVM module | The main classes (section 4). |
| `checkClassReferences<Variant>` | each Android module | The classes of each variant (section 4). |
| `checkLibraryArtifact` | each library | The jar or the release AAR (section 6). |

- A violation fails the task with one line for each violation, which names the rule's `reason` where the rule has one.
- Each of the checks of the modules reports to a build service when it passes, and `checkBoundaries` fails, naming the check, if one that the plugin registered did not report in the same build: a check disabled, skipped with `onlyIf`, stopped before its action, or left out with `-x` fails the build rather than passing unseen. The checks have no outputs, so Gradle runs them in every build that asks for them ([decisions/0061](../decisions/0061-check-boundaries-requires-every-check.md)).
- The root project is not checked: it only aggregates the API reference of the library modules, and ships nothing. Its build file is under security review like every other.

## 2. The rules file

`config/boundaries.toml` is the single place of the rules. The build logic reads it with Jackson's TOML module (`jackson-dataformat-toml`), and the reader fails, instead of ignoring them, on a key it does not know, on a value of the wrong type, on a module name in `allowed_dependencies`, `allowed_modules`, or `modules` that is not a declared module, and on an entry of `restricted_classes` that names nothing.

Names are the JVM's internal names: classes as `android/content/Intent`, nested classes as `java/util/Map$Entry`, packages as `java/lang/reflect`. A member is written `owner.name`, for any descriptor, or `owner.name(descriptor-prefix` for the descriptors that start with what follows the name (`android/content/Context.getSystemService(Ljava/lang/Class;)`). Kotlin's top-level declarations are members of their file's class (`LocalClipboard` is `androidx/compose/ui/platform/CompositionLocalsKt.getLocalClipboard`).

| Key | Holds | Section |
|---|---|---|
| `modules.<name>.allowed_dependencies` | The modules that the module may depend on. | 3 |
| `modules.<name>.check_classes` | Whether the module's classes are checked. | 4.1 |
| `modules.<name>.allowed_references` | The module's allowlist; required when `check_classes` is true, and not allowed otherwise. | 5 |
| `baseline_references` | The allowlist that every checked module has besides its own. | 5.2 |
| `restricted_members` | Classes whose fields and methods only some modules may use. | 4.4 |
| `restricted_classes` | Classes, packages, and members that only some modules may refer to at all. | 4.5 |
| `reserved_packages` | Packages in which only some modules may declare classes. | 4.6 |
| `bootstrap_methods` | The bootstrap methods that dynamic call sites and constants may use. | 4.7 |

## 3. Dependencies between modules

- **The projects and the modules are the same.** A project of the build that is not declared in `modules`, and a declared module that is not a project, are violations. A nested project (such as `:vtfx-gate:x`) has no entry, and so is a violation.
- **Declared dependencies.** For every configuration of every project, the project dependencies it declares are checked against the project's `allowed_dependencies`.
- **Resolved dependencies.** For every configuration that can be resolved, every edge to a project in the resolved graph is checked: from the project itself, and from every component it reaches. This sees what the declarations do not: dependencies added by plugins or on resolution (`withDependencies`, `defaultDependencies`), and substitutions.
  - An edge from a project of the build is checked against that project's `allowed_dependencies`.
  - An edge from a module of a repository (or from files) to a project is a violation however it came about, and so is an edge to a project of another build, such as an included build.
  - A dependency that does not resolve still counts as an edge to the project it requested.
  - Edges to modules of repositories are not checked here; external dependencies are reviewed with dependency verification ([decisions/0035](../decisions/0035-dependency-verification.md)), and what the classes use of them is checked by section 5.
- **An edge from a project to itself is allowed** (the tests of `vtfx-core` depend on its own test fixtures).
- **Tests have no list of their own.** The configurations of the tests and test fixtures are checked against the same `allowed_dependencies` as the main code.

## 4. Compiled classes

### 4.1 Which classes are read

- Each module says with `check_classes` whether its classes are checked. Every library module is checked; `vtfx-sample` is not: it plays a third-party app, which may use any capability.
- The check reads the main classes that the module ships: in the JVM modules, the output of the source set `main`; in the Android modules, AGP's `ScopedArtifact.CLASSES` (Kotlin and Java) of each variant, in one task per variant. Class files under `META-INF/`, in directories and jars alike, are skipped. Every class file that a library's artifact ships must be one of the classes read here (section 6); both checks take the classes from one function, so that they agree on which were read.
- Two class files at the same path in different directories or jars fail the check, as does a class file that is not at the path of the class it holds: the supertypes are found by path (section 4.3), and the artifact is compared by path (section 6), so either could see another class than the one that was checked.
- The classes of tests are not read: they are not shipped, and fakes in them may implement anything.
- **The classes of the project** are the module's own and those of the projects on its compile classpath (for Android modules, the classes jars that AGP makes of them), by their exact names, so that a class of a library under a name of vtfx is not taken for one of the project. Only the rules of section 4 apply to references to them; the allowlists of section 5 cover references outside the project.

### 4.2 What is read from a class

From each class file, the check reads with ASM, skipping debug information:

- **Types**: every class named anywhere: the superclass and interfaces, descriptors and generic signatures of fields, methods, and record components, declared exceptions, annotations (including type annotations, and classes and enums in their values), the types of instructions, caught exceptions, constants, the owners of members used, and the descriptors of dynamic call sites and constants. Arrays count as their element types; primitive types are left out.
- **Members**: every field and method used, through the owner named at the use: in instructions, in method handles, and in the arguments of bootstrap methods. A method of an array (such as `clone`) is a use through the array's element type.
- **Bootstrap methods** of dynamic call sites and constants. The types of their own descriptors, which name classes of `java/lang/invoke`, are not added to the types; they are checked against `bootstrap_methods` alone (section 4.7).

### 4.3 Supertypes

The rules that apply "through any subtype", and the members a class inherits, need the supertypes of the classes referred to. They are read from the class files of the module's classes, of what it is compiled against, and, for the Android modules, of the boot class path (`android.jar`), and then from the platform that runs the build. A class that none of these has fails the check, instead of being taken to have no supertypes; so does a member that neither its owner nor any supertype of the owner declares.

The class that declares a member used through some owner is the one the JVM resolves the use to (JVMS 5.4.3.2 to 5.4.3.4): for a method, the owner itself or the nearest of its superclasses that declares it, and only then the nearest of the interfaces of all of them; for a field, the owner itself, then each of its interfaces with their superinterfaces, then its superclass, looked up in the same way.

A class also uses, through the superclass that declares it, each method that it inherits from a superclass to implement a method of an interface that it implements and its superclass does not. A call through the interface reaches that method, though neither the class nor the call names it there; the rules of sections 4.4, 4.5, and 5.1 then apply to it as to any member used. Interfaces that the superclass implements already open up nothing new, and a project's superclass that adds the interface is checked for the methods itself.

### 4.4 Restricted members

`restricted_members` lists classes whose fields and methods only the `allowed_modules` may use, through the class or any subtype of it. Other modules may still name the classes as types, for example to pass an instance on. A use is matched by the owner named at the use (section 4.2), so any member used through such a type counts, including the methods of `Object`, which no other module needs to call on them.

| Classes | Allowed modules | Why |
|---|---|---|
| `HostConnection` | vtfx-gate | Only vtfx-gate writes to the host ([decisions/0027](../decisions/0027-session-takes-connection.md)). |
| `ClipboardAccess`, `UrlOpener` | vtfx-gate, vtfx-gate-android | Only vtfx-gate calls them, and vtfx-gate-android implements them ([decisions/0083](../decisions/0083-gate.md)). |

This is how `TerminalSession` in vtfx-session takes the connection, the clipboard, and the URL opener from the app and hands them over to vtfx-gate: any file of vtfx-session may name them as types, and none may call them.

### 4.5 Restricted classes

`restricted_classes` lists what only the `allowed_modules` may refer to at all: `classes` (with their nested classes), `packages` (with their subpackages), both matched against the types of section 4.2, and `members`, matched against the members used through the member's owner or any subtype of it. An entry may hold any of the three, and must hold at least one. The entries cover:

| What | Allowed modules |
|---|---|
| The clipboard, including Compose's (`LocalClipboard`, `LocalClipboardManager`, `ClipEntry`) | vtfx-gate-android |
| Opening other apps: `Intent`, `PendingIntent`, the `startActivity` family of `Context`, and Compose's and AndroidX's ways that do not name `Intent` (`LocalUriHandler`, `UriHandler`, `LinkAnnotation.Url`, `ShareCompat`, `CustomTabsIntent`) | vtfx-gate-android |
| `AndroidClipboard` and `AndroidUrlOpener`, which only apps make, and which vtfx code reaches through their interfaces in vtfx-gate ([decisions/0083](../decisions/0083-gate.md)) | vtfx-gate-android |
| Fonts that do not come from `SanitizedFont`: `android/graphics/fonts`, the builders of `Typeface` and its `createFromAsset` and `createFromFile`, `ParcelFileDescriptor`, Compose's fonts from files (`AndroidFont`, `AndroidFontKt`, whose `Font(File)` and `Font(ParcelFileDescriptor)` have mangled names, `LoadedFontFamily`, `FontFamily(Typeface)`), and downloadable Google Fonts ([decisions/0020](../decisions/0020-fonts.md), [decisions/0040](../decisions/0040-sanitized-user-fonts.md)) | vtfx-font-gate-android |
| `Context.getSystemService(String)`, which names a service by a string and so gets past the rules on its class | none |
| Reflection and loading classes: `java/lang/reflect`, `java/lang/invoke`, `kotlin/reflect/full`, `kotlin/reflect/jvm`, `dalvik/system`, `ClassLoader`, and the methods of `Class` that look up or load classes, constructors, fields, and methods | none |
| Processes: `Process`, `ProcessBuilder`, `ProcessHandle`, `Runtime` | none |
| The network: `java/net`, `javax/net`, `android/webkit`, `okhttp3`, `DownloadManager` | none |
| The platform's image decoders (`BitmapFactory`, `ImageDecoder`), which must not see images from the host ([decisions/0080](../decisions/0080-kitty-graphics.md)) | none |

The exact names are in `config/boundaries.toml`.

### 4.6 Reserved packages

`reserved_packages` lists packages, with their subpackages, in which only the listed modules may declare classes, checked on the names of the compiled classes. Another module could otherwise declare a class of the same name and replace a reviewed one on the class path ([decisions/0041](../decisions/0041-security-review-by-module.md)).

| Package | Modules |
|---|---|
| `com.google.vtfx.font.gate` | vtfx-font-gate, vtfx-font-gate-android |
| `com.google.vtfx.gate` | vtfx-gate, vtfx-gate-android |

### 4.7 Bootstrap methods

Every bootstrap method of a dynamic call site or constant must match an entry of `bootstrap_methods`, matched like a member (section 2). The list holds those that the Kotlin and Java compilers use for lambdas (`LambdaMetafactory.metafactory`, `altMetafactory`) and string concatenation (`StringConcatFactory.makeConcatWithConstants`).

## 5. Allowlists

Every checked module has an allowlist, `allowed_references`, which together with `baseline_references` (section 5.2) lists everything outside the project (section 4.1) that the module's classes may refer to. A reference to a class or member outside the project that neither allows fails, so an API that no rule names cannot get in unseen. The allowlists come on top of section 4: a reference that an allowlist allows can still break a rule of section 4.

### 5.1 Semantics

An allowlist has four lists:

- `classes`: classes allowed with all their members. Their nested classes are not included.
- `packages`: the classes directly in the packages, with all their members. Classes in subpackages are not included.
- `types`: classes that may only be named as types; their members may not be used unless another entry allows them.
- `members`: members that may be used, written as in section 2, through the owner or any subtype of it. The owners and their subtypes may then be named as types.

A type outside the project is allowed if `classes` or `packages` allow it, if `types` lists it, or if it is a subtype of the owner of an entry of `members`.

A member outside the project is allowed if `classes` or `packages` allow its owner, if an entry of `members` matches it, or if `classes` or `packages` allow the class that declares it (section 4.3), so that the members of `Object` may be used through any class.

A member used through a class of the project that the class inherits from a class outside the project counts as a use of the member through the class that declares it, which an allowlist must then allow. A module's own subclass of a platform class therefore does not open up the platform class's members, neither when they are used through the subclass nor through an interface of the project that the subclass implements with them (section 4.3).

### 5.2 The baseline

`baseline_references` holds what every checked module may use and what the compiler emits: the packages of the Kotlin language and standard library without capabilities (`kotlin`, `kotlin/collections`, `kotlin/text`, `kotlin/jvm/internal`, and the like), the boxes, exceptions, and annotation classes of `java.lang`, the collections of `java.util`, and the nullability annotations of JetBrains. It also has `Class` as a type only (`Enum.valueOf` takes the `Class` of the enum), and `Thread.currentThread`, which `HostWriter` and `TerminalSession` use to refuse reentrant calls from the same thread ([decisions/0024](../decisions/0024-thread-conventions.md)).

It leaves out `kotlin.io`, `kotlin.concurrent`, `kotlin.reflect`, `System`, `Runtime`, `ClassLoader`, and `java.io`, which a module must list itself if it needs them.

### 5.3 The modules' lists

- The JVM modules list the few classes they need beyond the baseline, for example `Inflater` in vtfx-core for the zlib data of images ([decisions/0080](../decisions/0080-kitty-graphics.md)), `ByteBuffer` and `MessageDigest` in vtfx-font-gate, `Thread.interrupted` in vtfx-font, and the locks of vtfx-session.
- vtfx-gate-android and vtfx-font-gate-android list classes one by one, so that each list is what the module may do: the clipboard and opening URLs (with `Context.startActivity` and `Context.getSystemService(Class)` by member), and making typefaces from buffers.
- vtfx-compose allows whole the packages of Compose without capabilities (runtime, layout, drawing, geometry, input events, semantics) and the coroutines of Kotlin, lists the classes of Android it draws and handles input with, and allows by member the classes that hold capabilities among others: `Context` (`getSystemService(Class)` only), `View`, `InputMethodManager`, `Bitmap` (`createBitmap`), `Typeface` (`create`), the composition locals of `androidx.compose.ui.platform`, among which are the clipboard and the URL handler, and `Log` (`w` only, since the log is a way out for information). The classes of vtfx-gate-android, which vtfx-compose depends on, are classes of the project, so its allowlist does not cover them (section 4.1); `restricted_classes` keeps `AndroidClipboard` and `AndroidUrlOpener`, all that vtfx-gate-android declares, to the app (section 4.5).

## 6. Library artifacts

`checkLibraryArtifact` checks what is published, the jar of each JVM library and the release AAR of each Android library, for what the checks of classes do not see and an app takes in by depending on the library. Any of these is a violation:

- In the manifest, which the app's manifest merges: the elements `uses-permission`, `uses-permission-sdk-23`, `permission`, `activity`, `activity-alias`, `service`, `receiver`, and `provider`.
- Native code: entries under `jni/` and files ending in `.so`.
- Files under `META-INF/services/`, through which `ServiceLoader` instantiates classes.
- `lint.jar`, which runs in the app's build.
- Any jar besides an AAR's `classes.jar`, whose classes the checks of classes do not read.
- Rules for R8, which apply to the app's classes: entries under `META-INF/proguard/` and `META-INF/com.android.tools/`, and an AAR's `proguard.txt`.
- A class file that is not, byte for byte, one of the classes that `checkClassReferences` reads (section 4.1; for an AAR, those of the release variant), wherever it is in the artifact, including under `META-INF/`. The jar of a JVM module also takes in its resources, and a class file among them would otherwise be shipped unchecked ([decisions/0060](../decisions/0060-artifacts-ship-only-checked-classes.md)).

An AAR's `classes.jar` is checked against the same list, as an artifact of its own.

The artifact is checked rather than the source directories, so that a change to the source sets, or a plugin, cannot route around it. `vtfx-sample` is not a library, and has no such check.

## 7. What the check does not cover

These are left to review, backed by being able to audit afterward (requirement 6.1):

- The build itself: build files, the build logic, the Gradle wrapper, and dependency verification can change what is checked. They are all under security review (`config/protected-paths.toml`), and so is `config/boundaries.toml`. Build files are every file that Gradle and its plugins read to configure the build, at any depth: Kotlin and Groovy scripts (a Groovy script takes the place of a Kotlin one in the same directory), `buildSrc/`, and `gradle.properties` and `local.properties`.
- How an allowed API is used: what is written to the log, what is shown to accessibility services, what a library that a module may use does inside. Tests and review keep checking these.
- How code outside review calls the reviewed modules, for example whether the text that it sends to the host is what the user typed. The gate checks what it can from the values; the rest is the caller's contract, guarded against mistakes rather than against changes written to get around it ([decisions/0075](../decisions/0075-adversaries-are-inputs.md)).
- The classes of tests, which are not shipped, and `vtfx-sample`, an app.
- A new external dependency is reviewed with its build file and `gradle/verification-metadata.xml` ([decisions/0035](../decisions/0035-dependency-verification.md)); using its classes then fails until an allowlist names them.
- A new version of Kotlin or of a library may change the references that the compiler emits, so an update can need changes to the lists, which are reviewed with the update.
