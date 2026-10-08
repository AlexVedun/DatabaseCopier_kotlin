# Database Copier

Desktop application (Kotlin + JavaFX) for copying database structure and data
between different DBMS engines, or from a raw SQL dump file into a live database.

## Features

- **Cross-dialect copy**: MySQL, PostgreSQL, Microsoft SQL Server and SQLite as
  both source and target, including between different engines (e.g. MySQL →
  PostgreSQL).
- **Dump file as source**: read directly from a `mysqldump`/`pg_dump` SQL file
  without first restoring it into a temporary database — the dump is streamed
  and indexed on disk instead of loaded into memory.
- **Structure copy**: tables, columns (with correct type mapping per target
  dialect), primary keys, indexes (including `FULLTEXT`/`SPATIAL`), `CHECK`
  constraints, foreign keys and views.
- **Resumable sessions**: copy progress (per-table cursor, row counts, which
  stage of each table is done) is persisted to a local SQLite database, so a
  paused, cancelled, or crashed session can be resumed from where it left off
  instead of restarting.
- **Safe by construction**: the app never attempts to build a generic
  value/dialect translator. It only transfers constructs it can represent
  faithfully, and only substitutes/skips a value when it can *prove* it has no
  valid representation on the target (e.g. MySQL's legacy zero-date
  `0000-00-00` on a `NOT NULL` column).
- **Encrypted credentials**: connection passwords are encrypted (AES/GCM) with
  a locally generated key before being stored, never in plain text.
- **Progress logging**: structured logs (SLF4J/Logback) to console and a
  size-capped rolling log file in the app's data directory.

## Requirements

- JDK 22+
- For running the JavaFX UI directly via Gradle: a desktop environment (X11/Wayland)
- Docker (optional, only needed to run the integration tests, which use
  Testcontainers against real MySQL/PostgreSQL/MSSQL containers)

## Building and running

```bash
./gradlew run
```

Run the test suite (unit + Testcontainers-based integration tests):

```bash
./gradlew test
```

## Native packaging

Native packages must be built on their target operating system. All packages are
self-contained and include a Java runtime.

Linux AppImage:

```bash
./gradlew appImage
```

Windows installer (run on Windows with WiX Toolset 3 installed):

```powershell
.\gradlew.bat windowsExe
```

macOS disk image (run on macOS):

```bash
./gradlew macDmg
```

The generated packages are written to:

```
build/appimage/database-copier-<version>-x86_64.AppImage
build/installer/windows/database-copier-<version>.exe
build/installer/macos/database-copier-<version>.dmg
```

Override the package version with `-PappVersion=1.2.3`. On JDK distributions
where `jlink` cannot create a runtime image, use `-PfullRuntime=true` to bundle
the current JDK as the runtime.

### GitHub releases

The `.github/workflows/release.yml` workflow runs the full test suite and builds
all three native packages on Linux, Windows and Apple Silicon macOS runners. Pushing
a numeric version tag such as `v1.2.3` starts the workflow. If every test and package
build succeeds, the workflow creates a GitHub Release named `1.2.3`, generates its
release notes automatically, and attaches the `.AppImage`, `.exe` and `.dmg` files.

The workflow can also be started manually from the Actions tab. A manual run
stores the packages as workflow artifacts but does not create or modify a
GitHub Release.

## Project layout

```
src/main/kotlin/com/example/databasecopier/
├── adapter/     JDBC source/target adapters, type mapping (LogicalType), JDBC URL building
├── dump/        Streaming SQL dump file reader/indexer used as an alternative source
├── copy/        CopyRunner — orchestrates structure + data copy, pause/resume, progress events
├── session/     Persistence of copy sessions and per-table/per-view progress
├── connection/  Saved connection profiles
├── security/    Credential encryption (AES/GCM)
└── ui/          JavaFX screens (source/target selection, table selection, progress)
```

## License

Not specified.
