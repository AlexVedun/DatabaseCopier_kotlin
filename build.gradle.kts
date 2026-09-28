plugins {
    kotlin("jvm") version "2.4.20"
    id("com.gradleup.shadow") version "9.6.1"
    application
}

group = "com.example"
// GitHub Actions передаёт версию из release-тега через -PappVersion. Обычная локальная сборка
// остаётся воспроизводимой и использует текущую версию проекта по умолчанию.
version = providers.gradleProperty("appVersion").orElse("1.2.0").get()

val osName = System.getProperty("os.name").lowercase()
val isLinux = osName.contains("linux")
val isWindows = osName.contains("windows")
val isMacOs = osName.contains("mac")
val architecture = System.getProperty("os.arch").lowercase()
val isArm64 = architecture == "aarch64" || architecture == "arm64"
val javafxPlatform = when {
    isLinux && isArm64 -> "linux-aarch64"
    isLinux -> "linux"
    isWindows && isArm64 -> "win-aarch64"
    isWindows -> "win"
    isMacOs && isArm64 -> "mac-aarch64"
    isMacOs -> "mac"
    else -> error("Unsupported operating system for JavaFX: ${System.getProperty("os.name")}")
}

repositories {
    mavenCentral()
}

dependencies {
    // OpenJFX содержит нативные библиотеки, поэтому release workflow собирает fat-jar отдельно
    // на каждой целевой ОС. Явный classifier гарантирует выбор библиотек нужной платформы.
    val javafxVersion = "25"
    implementation("org.openjfx:javafx-base:$javafxVersion:$javafxPlatform")
    implementation("org.openjfx:javafx-graphics:$javafxVersion:$javafxPlatform")
    implementation("org.openjfx:javafx-controls:$javafxVersion:$javafxPlatform")
    implementation("org.openjfx:javafx-fxml:$javafxVersion:$javafxPlatform")

    // Kotlin coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-javafx:1.8.1")

    // Kotlin serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    // Exposed for service database
    implementation("org.jetbrains.exposed:exposed-core:0.41.1")
    implementation("org.jetbrains.exposed:exposed-dao:0.41.1")
    implementation("org.jetbrains.exposed:exposed-jdbc:0.41.1")

    // Database drivers
    implementation("org.xerial:sqlite-jdbc:3.46.0.0")
    implementation("com.mysql:mysql-connector-j:8.4.0")
    implementation("org.postgresql:postgresql:42.7.3")
    implementation("com.microsoft.sqlserver:mssql-jdbc:13.2.1.jre11")

    // Logging
    implementation("ch.qos.logback:logback-classic:1.5.13")

    // Testing
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testImplementation("org.testcontainers:testcontainers:1.21.3")
    testImplementation("org.testcontainers:junit-jupiter:1.21.3")
    testImplementation("org.testcontainers:mysql:1.21.3")
    testImplementation("org.testcontainers:postgresql:1.21.3")
    testImplementation("org.testcontainers:mssqlserver:1.21.3")
}

tasks.test {
    useJUnitPlatform()
    // JNA/SQLite load native libraries in tests. JDK 25 warns that this access will be blocked in
    // a future release unless it is explicitly enabled for classpath (unnamed-module) code.
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    // Docker Engine 29.x на этой машине отклоняет старую версию Docker API (1.32), которую
    // testcontainers/docker-java по умолчанию использует при первичной проверке доступности
    // демона — принудительно используем более новую версию API.
    systemProperty("api.version", "1.41")
}

application {
    mainClass.set("com.example.databasecopier.MainKt")
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_22)
    }
}

tasks.withType<JavaCompile> {
    options.release.set(22)
}

// Шаг 14: упаковка через jpackage в самодостаточный app-image (встроенный JRE, не требует
// установленного JDK на машине пользователя). shadowJar собирает один fat-jar со всеми
// зависимостями (включая JavaFX-модули текущей платформы, выбранные classifier-зависимостями)
// — jpackage поддерживает только классический classpath-запуск
// (--main-jar/--main-class), не модульный, поэтому JPMS module-info здесь не нужен.
// Каждый JDBC-драйвер регистрирует себя через META-INF/services/java.sql.Driver — по умолчанию
// shadowJar при слиянии jar'ов берёт только один такой файл (последний по порядку), из-за чего
// ServiceLoader/DriverManager видит только один драйвер, а не все четыре. mergeServiceFiles()
// вместо перезаписи объединяет содержимое одноимённых файлов META-INF/services/* из всех jar'ов.
tasks.shadowJar {
    // Shadow 9 по умолчанию исключает дубликаты ещё до transformers. Для service descriptors
    // это оставило бы в fat-jar только один JDBC-драйвер вместо объединения всех провайдеров.
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    // Несколько зависимостей содержат одноимённый общий LICENSE.txt. В итоговом архиве достаточно
    // первого экземпляра; локальное правило не мешает mergeServiceFiles() ниже.
    filesMatching("META-INF/LICENSE.txt") {
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    }
    mergeServiceFiles()
}

val jpackageInputDir = layout.buildDirectory.dir("jpackage-input")
val packageName = "database-copier"
val packageVendor = "Database Copier"
val packageDescription = "Copy database structure and data between different DBMS engines"

// На обычных Temurin-сборках jpackage сам создаёт компактный runtime через jlink. Локальный
// Red Hat JDK изменяет java.security системной crypto policy, из-за чего jlink отказывается
// работать; для него сохраняем проверенный fallback с полным runtime. Поведение можно явно
// переопределить через -PfullRuntime=true/false.
val useFullRuntime = providers.gradleProperty("fullRuntime")
    .map(String::toBoolean)
    .orElse(System.getProperty("java.vendor").contains("Red Hat", ignoreCase = true))

fun jpackageRuntimeArgs(): List<String> = if (useFullRuntime.get()) {
    listOf("--runtime-image", System.getProperty("java.home"))
} else {
    // Начиная с JDK 25 jpackage больше не передаёт --bind-services в jlink автоматически.
    // JDBC и crypto providers используют ServiceLoader, поэтому сохраняем service bindings.
    listOf(
        "--jlink-options",
        "--strip-native-commands --strip-debug --no-man-pages --no-header-files --bind-services",
    )
}

fun verifyJpackageRuntime() {
    if (!useFullRuntime.get()) {
        val jmodsDirectory = file(System.getProperty("java.home")).resolve("jmods")
        check(jmodsDirectory.isDirectory) {
            "Compact runtime packaging requires a JDK with JMOD files at $jmodsDirectory. " +
                "For Temurin 24+ use actions/setup-java with java-package: jdk+jmods, " +
                "or pass -PfullRuntime=true to package the current runtime."
        }
    }
}

fun jpackageCommonArgs(type: String, destination: File): List<String> = listOf(
    "jpackage",
    "--type", type,
    "--name", packageName,
    "--app-version", project.version.toString(),
    "--vendor", packageVendor,
    "--description", packageDescription,
    "--input", jpackageInputDir.get().asFile.absolutePath,
    "--main-jar", tasks.shadowJar.get().archiveFileName.get(),
    "--main-class", "com.example.databasecopier.MainKt",
    "--dest", destination.absolutePath,
) + jpackageRuntimeArgs()

val prepareJpackageInput = tasks.register<Sync>("prepareJpackageInput") {
    dependsOn(tasks.shadowJar)
    from(tasks.shadowJar)
    into(jpackageInputDir)
}

val jpackageAppImage = tasks.register<Exec>("jpackageAppImage") {
    group = "distribution"
    description = "Собирает самодостаточный app-image (Linux) через jpackage"
    dependsOn(prepareJpackageInput)

    val outputDir = layout.buildDirectory.dir("jpackage/linux")

    // jpackage отказывается писать в уже существующий "<dest>/<name>" (падает с "Application
    // destination directory ... already exists") — без очистки задача несостоятельна при повторном
    // запуске, что ломает appImage при каждом втором ./gradlew appImage подряд.
    doFirst {
        check(isLinux) { "Задачу appImage необходимо запускать на Linux" }
        verifyJpackageRuntime()
        delete(outputDir)
        outputDir.get().asFile.mkdirs()
    }

    commandLine(*jpackageCommonArgs("app-image", outputDir.get().asFile).toTypedArray())
}

val windowsExe = tasks.register<Exec>("windowsExe") {
    group = "distribution"
    description = "Собирает установщик Windows (.exe) через jpackage"
    dependsOn(prepareJpackageInput)

    val outputDir = layout.buildDirectory.dir("installer/windows")
    doFirst {
        check(isWindows) { "Задачу windowsExe необходимо запускать на Windows" }
        verifyJpackageRuntime()
        delete(outputDir)
        outputDir.get().asFile.mkdirs()
    }

    commandLine(
        *(jpackageCommonArgs("exe", outputDir.get().asFile) + listOf(
            "--win-dir-chooser",
            "--win-menu",
            "--win-menu-group", packageVendor,
            "--win-shortcut",
        )).toTypedArray()
    )
}

val macDmg = tasks.register<Exec>("macDmg") {
    group = "distribution"
    description = "Собирает установочный образ macOS (.dmg) через jpackage"
    dependsOn(prepareJpackageInput)

    val outputDir = layout.buildDirectory.dir("installer/macos")
    doFirst {
        check(isMacOs) { "Задачу macDmg необходимо запускать на macOS" }
        verifyJpackageRuntime()
        delete(outputDir)
        outputDir.get().asFile.mkdirs()
    }

    commandLine(
        *(jpackageCommonArgs("dmg", outputDir.get().asFile) + listOf(
            "--mac-package-identifier", "com.example.databasecopier",
        )).toTypedArray()
    )
}

// Шаг 17: jpackage --type app-image даёт КАТАЛОГ (bin/ + lib/ со встроенным JRE), а не единый файл —
// оборачиваем этот каталог в AppDir (AppRun + .desktop + иконка на верхнем уровне, см.
// packaging/appimage/) и собираем его в настоящий однофайловый .AppImage через appimagetool.
val appImageDir = layout.buildDirectory.dir("AppDir")

val prepareAppDir = tasks.register<Sync>("prepareAppDir") {
    group = "distribution"
    description = "Собирает AppDir (jpackage app-image + AppRun/.desktop/иконка) для appimagetool"
    dependsOn(jpackageAppImage)

    from(layout.buildDirectory.dir("jpackage/linux/database-copier"))
    from("packaging/appimage") {
        include("AppRun", "database-copier.png")
    }
    from("packaging/appimage") {
        include("database-copier.desktop")
        expand("appVersion" to project.version.toString())
    }
    into(appImageDir)

    // Встроенный JRE от jpackage содержит read-only CDS-архивы (*.jsa, права 444) — при повторном
    // запуске Sync не может перезаписать уже существующий read-only файл в AppDir тем же именем
    // ("Відмовлено у доступі"). Проще снести AppDir целиком перед синком, чем разбираться, какие
    // файлы read-only.
    doFirst {
        val dir = appImageDir.get().asFile
        if (dir.exists()) {
            dir.walkBottomUp().forEach { it.setWritable(true) }
            dir.deleteRecursively()
        }
    }

    // Sync копирует права доступа как есть, но AppRun/desktop-файл в git всегда без exec-бита —
    // appimagetool требует, чтобы AppRun был исполняемым, иначе получившийся .AppImage не запустится.
    doLast {
        appImageDir.get().file("AppRun").asFile.setExecutable(true)
        appImageDir.get().file("database-copier.desktop").asFile.setExecutable(true)
    }
}

val appImage = tasks.register<Exec>("appImage") {
    group = "distribution"
    description = "Собирает однофайловый .AppImage (Linux) из AppDir через appimagetool"
    dependsOn(prepareAppDir)

    // appimagetool сам по себе распространяется как AppImage; путь переопределяется через
    // -PappimagetoolPath=..., по умолчанию берётся из домашней директории пользователя.
    val appimagetoolPath = project.findProperty("appimagetoolPath")?.toString()
        ?: "${System.getProperty("user.home")}/appimagetool.AppImage"

    val outputDir = layout.buildDirectory.dir("appimage")
    val outputFile = outputDir.get().file("database-copier-${project.version}-x86_64.AppImage")

    doFirst {
        check(isLinux) { "Задачу appImage необходимо запускать на Linux" }
        outputDir.get().asFile.mkdirs()
        check(File(appimagetoolPath).exists()) {
            "appimagetool не найден по пути $appimagetoolPath — передайте -PappimagetoolPath=<путь>"
        }
    }

    // --appimage-extract-and-run вместо прямого запуска appimagetool: сам appimagetool — это
    // AppImage, а его штатный способ монтирования через FUSE недоступен в песочницах/контейнерах
    // без /dev/fuse — extract-and-run распаковывает и запускает без монтирования.
    commandLine(
        appimagetoolPath,
        "--appimage-extract-and-run",
        appImageDir.get().asFile.absolutePath,
        outputFile.asFile.absolutePath,
    )

    doLast { println("Готово: ${outputFile.asFile.absolutePath}") }
}
