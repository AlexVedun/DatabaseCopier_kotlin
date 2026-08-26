plugins {
    kotlin("jvm") version "2.1.20"
    id("org.openjfx.javafxplugin") version "0.1.0"
    id("com.gradleup.shadow") version "8.3.5"
    application
}

group = "com.example"
version = "1.0.0"

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
    implementation("com.microsoft.sqlserver:mssql-jdbc:12.6.1.jre11")

    // Logging
    implementation("ch.qos.logback:logback-classic:1.5.6")

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

    doFirst { outputDir.get().asFile.mkdirs() }

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
