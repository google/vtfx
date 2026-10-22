# Library artifacts ship only the checked classes and no rules for R8

- Status: Provisional
- Date: 2026-10-22
- Related: Requirement 6.1, [0041](0041-security-review-by-module.md), [0044](0044-boundaries-in-gradle-and-classes.md), sections 4.1 and 6 of [boundaries.md](../design/boundaries.md)

## Background

The check of classes reads the compiled classes of a module: the output of the source set `main` in the JVM modules, and AGP's `ScopedArtifact.CLASSES` in the Android modules. The jar of a JVM module also takes in its resources, so a file ending in `.class` under `src/main/resources` was shipped without being read. Such a class could use anything, and could take a name in a reserved package and replace a reviewed class of a gate module on the class path, which [0041](0041-security-review-by-module.md) and section 4.6 of [boundaries.md](../design/boundaries.md) mean to prevent. The resources could also hold rules for R8 (`META-INF/proguard/`, `META-INF/com.android.tools/`), which R8 takes from every jar into the app's build, and with which an app's release build could drop or change the calls of the gate modules. AGP leaves both out of an AAR today, but nothing checked that it does.

## Decision

1. **Every class file that a library's artifact ships must be one that the check of classes read, byte for byte**: those of the jar of a JVM module, and those of the `classes.jar` of an AAR, against the classes that `checkClassReferences` reads (for an AAR, those of the release variant). `checkLibraryArtifact` reads both and fails on any other class file, wherever in the artifact it is, including under `META-INF/` (section 6 of [boundaries.md](../design/boundaries.md)). The check stays on the published artifact rather than on the source directories, as in [0044](0044-boundaries-in-gradle-and-classes.md), so it holds for whatever the build puts in.
2. **Rules for R8 are not allowed in an artifact**: entries under `META-INF/proguard/` and `META-INF/com.android.tools/`, and an AAR's `proguard.txt`. No library has rules today. A library that needs them later needs a human to agree to a way of reviewing them, since they change the classes of the app rather than of the library.
3. **An AAR's `classes.jar` is checked like an artifact of its own**, with all the rules of section 6, since R8 and the app's build read it like any other jar.

## Alternatives considered

- **Have `checkClassReferences` of the JVM modules read the jar instead of the classes directories**: it reads the shipped classes directly, but does not cover the `classes.jar` of an AAR, and keeps two different ways of tying the check to what is shipped.
- **Compare only the names of the classes**: a resource of the same name as a compiled class would go into the jar as a second entry, and the class loader takes one of them; comparing the bytes leaves no choice to it.
- **Reject `.class` files and rules only in `src/main/resources`, or add those directories to `config/protected-paths.toml`**: a change to the source sets or a plugin can add files from elsewhere, and a report leaves it to a reviewer to notice a binary file.
- **Allow rules for R8 and put them under review**: no library needs them, and their effect on the app is hard to review.

## Impact of reversing

- Remove the comparison with the checked classes, the rules for R8, and the check of `classes.jar` from `LibraryArtifacts.kt`, the inputs of the classes from `CheckLibraryArtifactTask` and `vtfx.boundaries.gradle.kts`, and their tests in `LibraryArtifactsTest.kt`; update section 6 of [boundaries.md](../design/boundaries.md).
