plugins {
    kotlin("jvm") version "2.1.20"
    id("org.openjfx.javafxplugin") version "0.1.0"
    id("com.gradleup.shadow") version "8.3.5"
    application
}

group = "com.example"
version = "1.1.0"

repositories {
    mavenCentral()
}

javafx {
    version = "25"
    modules = listOf("javafx.controls", "javafx.fxml")
}

dependencies {
    // JavaFX (managed by plugin)

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
// зависимостями (включая JavaFX-модули текущей платформы, которые javafx-плагин уже подключил
// как обычные classpath-зависимости) — jpackage поддерживает только классический classpath-запуск
// (--main-jar/--main-class), не модульный, поэтому JPMS module-info здесь не нужен.
// Каждый JDBC-драйвер регистрирует себя через META-INF/services/java.sql.Driver — по умолчанию
// shadowJar при слиянии jar'ов берёт только один такой файл (последний по порядку), из-за чего
// ServiceLoader/DriverManager видит только один драйвер, а не все четыре. mergeServiceFiles()
// вместо перезаписи объединяет содержимое одноимённых файлов META-INF/services/* из всех jar'ов.
tasks.shadowJar {
    mergeServiceFiles()
}

val jpackageInputDir = layout.buildDirectory.dir("jpackage-input")

val prepareJpackageInput by tasks.registering(Sync::class) {
    dependsOn(tasks.shadowJar)
    from(tasks.shadowJar)
    into(jpackageInputDir)
}

val jpackageAppImage by tasks.registering(Exec::class) {
    group = "distribution"
    description = "Собирает самодостаточный app-image (Linux) через jpackage"
    dependsOn(prepareJpackageInput)

    val outputDir = layout.buildDirectory.dir("jpackage")
    val mainJarName = tasks.shadowJar.get().archiveFileName.get()

    // jpackage отказывается писать в уже существующий "<dest>/<name>" (падает с "Application
    // destination directory ... already exists") — без очистки задача несостоятельна при повторном
    // запуске, что ломает appImage при каждом втором ./gradlew appImage подряд.
    doFirst {
        delete(outputDir.get().dir("database-copier"))
        outputDir.get().asFile.mkdirs()
    }

    commandLine(
        "jpackage",
        "--type", "app-image",
        "--name", "database-copier",
        "--app-version", project.version.toString(),
        "--input", jpackageInputDir.get().asFile.absolutePath,
        "--main-jar", mainJarName,
        "--main-class", "com.example.databasecopier.MainKt",
        "--dest", outputDir.get().asFile.absolutePath,
        // Без --runtime-image jpackage сам вызывает jlink, чтобы собрать урезанный runtime — на
        // сборках OpenJDK от Fedora/Red Hat это падает с "java.security has been modified"
        // (постустановочный скрипт правит java.security для system-wide crypto policy, из-за чего
        // jlink не может создать кастомный образ). Переиспользуем полный JDK текущей сборки как
        // готовый runtime-image — app-image получается крупнее, зато не зависит от этого багфикса.
        "--runtime-image", System.getProperty("java.home"),
    )
}

// Шаг 17: jpackage --type app-image даёт КАТАЛОГ (bin/ + lib/ со встроенным JRE), а не единый файл —
// оборачиваем этот каталог в AppDir (AppRun + .desktop + иконка на верхнем уровне, см.
// packaging/appimage/) и собираем его в настоящий однофайловый .AppImage через appimagetool.
val appImageDir = layout.buildDirectory.dir("AppDir")

val prepareAppDir by tasks.registering(Sync::class) {
    group = "distribution"
    description = "Собирает AppDir (jpackage app-image + AppRun/.desktop/иконка) для appimagetool"
    dependsOn(jpackageAppImage)

    from(layout.buildDirectory.dir("jpackage/database-copier"))
    from("packaging/appimage") {
        include("AppRun", "database-copier.desktop", "database-copier.png")
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

val appImage by tasks.registering(Exec::class) {
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
