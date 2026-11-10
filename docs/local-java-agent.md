# Machine-local Java agent for the sandbox IDE

How to attach a `-javaagent` (and any other extra JVM arguments) to the IDE launched by
`./gradlew runIde` **without ever committing the agent path to the repository**.

The mechanism already lives in `build.gradle.kts`: the `sandboxExtraJvmArgs` provider reads the
machine-local value and appends it to every task that forks an IDE JVM:

| Task | Gets the arguments |
|------|--------------------|
| `runIde` | yes |
| `runIdeForUiTests` | yes |
| `heavyTest` | yes |

The repository contains only the generic property name — never the path.

## 1. Put the value in `~/.gradle/gradle.properties`

This file lives in the Gradle user home, outside any repository, and is never committed.

```properties
# Space-separated JVM arguments for the IDE launched by runIde/runIdeForUiTests/heavyTest.
# The agent path must be absolute and must not be quoted.
ideaSandboxJvmArgs=-javaagent:/absolute/path/to/agent.jar=agentOptions
```

Several arguments are allowed — separate them with spaces:

```properties
ideaSandboxJvmArgs=-javaagent:/absolute/path/to/agent.jar=opt1,opt2 --add-opens=java.base/some.package=ALL-UNNAMED
```

Rules:

- The path must be absolute and without quotes.
- Agent options go after `=`, e.g. `-javaagent:/p/a.jar=opt1,opt2`.
- The value is split on whitespace, so paths must not contain spaces.

## 2. Alternatives

Environment variable (same value, per-shell):

```bash
export IDEA_SANDBOX_JVM_ARGS="-javaagent:/absolute/path/to/agent.jar=jetbrains"
./gradlew runIde
```

One-off command line:

```bash
./gradlew runIde "-PideaSandboxJvmArgs=-javaagent:/absolute/path/to/agent.jar"
```

## 3. Verify

Start the sandbox IDE:

```bash
./gradlew runIde
```

Then check the actual command line of the IDE process:

```bash
pgrep -f "ideaIU-.*/jbr/bin/java" | head -1
tr '\0' '\n' < /proc/<pid>/cmdline | grep -E "^-javaagent"
```

Many agents also print a banner into the IDE stdout, which Gradle shows:

```bash
./gradlew runIde 2>&1 | grep -i <agent-name>
```

### `JVM options:` in `idea.log` can lie

The `JVM options:` line in
`.intellijPlatform/sandbox/per-line-coverage-info/<version>/log/idea.log` is
`ManagementFactory.getRuntimeMXBean().getInputArguments()`. Some agents — ja-netfilter is one —
**erase themselves from that list on purpose**, so they never appear there even when loaded.
If `/proc/<pid>/cmdline` shows the `-javaagent` argument and the agent prints its banner, the
agent works; do not rely on the `JVM options:` line for such agents.

## 4. Why this never lands in git

- The value lives in `~/.gradle/gradle.properties` (or env / `-P` on the command line) — all
  outside version control.
- `build.gradle.kts` only references the property name `ideaSandboxJvmArgs` and a generic
  placeholder example; it contains no machine paths.
- Do **not** put `ideaSandboxJvmArgs` into the project's `gradle.properties` — that file is
  committed.

## 5. Related

- `syncIdeLicense` copies local IDE license keys into the sandbox config before `runIde` —
  also machine-local, also never committed. See `AGENTS.md`, section *Build & Run*.
- The 30-second scripted check that boots the IDE, verifies the plugin and the agent, and
  stops the IDE can be kept locally, e.g. as `/tmp/verify-agent.sh`.
