# Code review feedback loop

This defines the procedure from a coding agent reviewing code and reporting bugs to fixing them in separate work.
A report is a handover document that the person fixing can re-verify without context.

## 1. Flow

1. A human decides who reviews (agent and session) and what, and requests the review.
2. The reviewer confirms bugs with reproduction tests, and puts the report and the reproduction tests in one commit (section 2). Product code is not changed.
3. The fixer fixes the items of the report one by one, and updates their status (section 3).
4. When all items are in a done state, the report is closed and removed (3.4).

The reviewer should preferably be a different agent or session from the one that wrote the code under review. Reading with the same assumptions as the writer tends to repeat the same oversights.

## 2. Procedure for the reviewer

### 2.1 Deciding the target

- The target is a range of commits (`BASE..HEAD`) or the whole tree of one commit. Write the full SHA in the report.
- When continuing an earlier review, use its target commit (in the commit that closed its report) as `BASE`.

### 2.2 Points of view

Prioritize in the following order. Do not report matters of taste in appearance or naming as bugs.

1. The security boundary (requirement 6.1, [architecture.md](../design/architecture.md)). Paths that reach the host without going through the gate, reflection of host output, resources without limits.
2. Crashes, hangs, sending wrong bytes to the host, losing input or displayed content.
3. Discrepancies with the contracts in the design documents ([ime-contract.md](../design/ime-contract.md), [decisions/](../decisions/README.md)).
4. Paths that are tested individually but whose combinations are not verified. Look especially at paths that cross module boundaries.

### 2.3 Reproducing

- Back every finding with a reproduction test that fails at the target commit. Do not report suspicions that could not be reproduced as bugs. Write what was checked and why it could not be reproduced in the "Unverified concerns" section of the report.
- Where possible, reproduction tests verify behavior observable through the public API (what is sent to the host, what is displayed). Tests that look only at internal state lose their meaning when the implementation changes after the fix.
- Put reproduction tests in `docs/reviews/repro-<date>/`, and do not include them in the normal `build` or `scripts/check.sh`. Putting tests that fail at the time of the report into the gate would make the gate fail. Run them with a Gradle init script, using the one below as a template. It is for the JVM module `vtfx-session`; change the module, the directory name, and the test file. For an Android module (e.g. `vtfx-compose`), use the tasks `testDebugUnitTest` and `compileDebugUnitTestKotlin` instead of `test` and `compileTestKotlin`. The module boundaries of `config/boundaries.toml` apply to the main classes of the modules only, so reproduction tests may use any API.

  ```groovy
  // Runs the review reproductions without adding them to the regular test suite.
  // See ../<date>-<name>.md for the command and expected failures.
  gradle.projectsEvaluated {
      def p = gradle.rootProject.findProject(':vtfx-session')
      if (p == null) return
      def sources = gradle.rootProject.file('docs/reviews/repro-<date>-<name>')
      def output = gradle.rootProject.layout.buildDirectory.dir('review-repros/<date>-<name>').get().asFile
      def original = p.tasks.named('test').get()
      def kotlin = p.tasks.named('compileTestKotlin').get()
      def compile = p.tasks.register('compileReviewRepro', JavaExec) {
          dependsOn kotlin
          classpath = p.configurations.getByName('kotlinCompilerClasspath')
          mainClass.set('org.jetbrains.kotlin.cli.jvm.K2JVMCompiler')
          inputs.file(new File(sources, 'ReviewReproTest.kt'))
          inputs.files(kotlin.libraries)
          outputs.dir(new File(output, 'classes'))
          doFirst {
              args = ['-no-stdlib', '-no-reflect', '-jvm-target', '17',
                      '-classpath', kotlin.libraries.asPath,
                      '-Xfriend-paths=' + kotlin.friendPaths.files.join(','),
                      '-opt-in=com.google.vtfx.core.InternalVtfxApi',
                      '-d', new File(output, 'classes').path,
                      new File(sources, 'ReviewReproTest.kt').path]
              kotlin.pluginClasspath.files.each { args('-Xplugin=' + it.path) }
          }
      }
      p.tasks.register('reviewRepro', Test) {
          dependsOn compile
          useJUnit()
          testClassesDirs = p.files(new File(output, 'classes'))
          classpath = p.files(new File(output, 'classes')) + original.classpath
          systemProperties original.systemProperties
          jvmArgs original.jvmArgs
          workingDir original.workingDir
          reports.html.outputLocation.set(new File(output, 'report'))
          reports.junitXml.outputLocation.set(new File(output, 'results'))
          testLogging { events 'passed', 'failed'; exceptionFormat 'full'; showStandardStreams true }
      }
  }
  ```

  The init script needs the Apache License header of the other source files (`scripts/check.sh` checks it). Run it with `--no-configuration-cache` (`./gradlew --no-configuration-cache --init-script <script> :vtfx-session:reviewRepro`): the compile task reads the task `compileTestKotlin` when it runs, which the configuration cache, enabled in `gradle.properties`, cannot store.
- Show the basis of the expected value with one of the requirements, design documents, decisions, spec tests, or the behavior of a reference terminal (xterm). If there is no basis and either behavior is plausible, write it not as a bug but as an item asking for a decision in section 1 of [review-queue.md](../review-queue.md).

### 2.4 Writing the report

- Copy [TEMPLATE.md](TEMPLATE.md) to `docs/reviews/<date>-<short English name>.md` and write it.
- Give items sequential IDs within the report (R1, R2, …). When referring from outside the report, write the report's file name together with the ID (e.g. R2 of `2026-10-12-bugs.md`). Only commit messages and other open reports refer to a report. Code, tests, decisions, and design documents do not, because the report is removed when it is closed; they explain the point in place instead.
- Assign a severity.
  - High: a breach of the security boundary, a crash, a hang, a wrong send to the host, loss of content
  - Medium: an error in display or input that the user can work around
  - Low: a small visual error, or a minor error that happens only under rare conditions
- Write the cause and the direction of the fix as the view at the time of review. Do not prescribe the implementation.
- Add the report to the list in section 4 as "Open".

### 2.5 Prohibitions

- Do not change product code, existing tests, `spec/`, `tools/ime-probe/traces/`, or goldens.
- Do not put reproduction tests in the normal test directories.

### 2.6 Commit

- Put the report, the reproduction tests, and the update of the list in section 4 in one commit. The subject is `docs: Report bugs found in a review of <target>`.
- Run `scripts/check.sh` before committing. In the `TEST=` line, write the result of check.sh, and the command and result of the reproduction tests (e.g. `6 tests completed, 6 failed`).

## 3. Procedure for the fixer

### 3.1 Starting

- When starting work, read the list in section 4, and if there are open reports, check their status.
- Fix high-severity items before milestone work. Medium and low items may be prioritized alongside milestone work.

### 3.2 Fixing one item

1. Run the reproduction test at the current HEAD and confirm that it still fails. If it already passes, find which commit fixed it and mark it "Not reproduced".
2. Read the contract and design documents cited in the report, and confirm that the expected value agrees with the contract. If it does not, either treat the contract as authoritative, or record a decision in [decisions/](../decisions/README.md) and revise the contract. If a human decision is needed, write it in section 1 of [review-queue.md](../review-queue.md) and mark the item "Awaiting decision".
3. Add a regression test that fails before the fix to the normal test directory. Check not only the steps in the report but also the surrounding cases listed under "Direction of the fix" in the report. Rather than copying the reproduction test as is, follow the existing test style of the module.
4. Fix it. Do not make tests pass by changing the expected values of the reproduction tests, `spec/`, or goldens.
5. Run `scripts/check.sh` and commit. One item per commit; items with the same cause may be put in one commit (say so in the status). Write the report's file name and ID in the commit body. Write the names of the tests that failed before the fix in the `TEST=` line.

If, during the fix, you notice that your own change introduced another bug (for example, short fuzzing found one), fix it before committing and write it in the commit message. Keep inputs found by fuzzing in the corpus (section 7 of [testing.md](../design/testing.md)).

### 3.3 Updating the status

Set the status table of the report to one of the following states.

| State | Meaning | What to write in the "Fix commit / verification" column |
|---|---|---|
| Open | Not started yet | — |
| Fixed | Fixed, with a regression test | The commit, the gist of the fix, the names of the tests that failed before the fix |
| Not reproduced | Does not happen at the current HEAD | The commit that fixed it, or why it does not reproduce |
| No action needed | The behavior was correct per the contract | The basis (links to decisions or design documents) |
| Awaiting decision | A human decision is needed | The item in section 1 of review-queue.md |

The table update may be included in the fix commit or gathered into a later documentation commit, since a commit cannot contain its own SHA.

The commit that sets the last item to a done state ("Fixed", "Not reproduced", or "No action needed") also closes the report (3.4), so that no report stays open with nothing left to do.

### 3.4 Closing the report

When all items are "Fixed", "Not reproduced", or "No action needed", do the following. While any item is "Awaiting decision", leave the report open.

1. Run the command in "Running the reproduction tests" of the report. The tests of "Fixed" and "Not reproduced" items must pass.
2. Check that nothing the report alone holds is lost: choices of behavior are in [decisions/](../decisions/README.md) or the design documents, open "Unverified concerns" are in section 1 of [review-queue.md](../review-queue.md) if a human needs to decide them, and no code, test, or document refers to the report.
3. Remove the report, `docs/reviews/repro-<date>/`, and its row in section 4 (reproduction tests need no entry in `config/boundaries.toml`; 2.3). The subject of the commit is `docs: Close the review report <date>-<name>`. Write the report's target commit and a summary of the status table in the body, and the result of step 1 in the `TEST=` line.

Closed reports and their reproduction tests are not kept in the tree, to keep the repository small and because what they record is kept elsewhere: the bugs by the regression tests in the normal tests, and the reasons by decisions and comments. The history before publication (2026-10-15) was squashed into one commit, so the reports closed before then are not kept anywhere. From publication on, the history is never rewritten, and a closed report can be read from the parent of its closing commit.

## 4. List of reports

Only open reports are listed.

| Report | Target | State |
|---|---|---|
