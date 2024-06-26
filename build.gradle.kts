
import java.net.URL


plugins {
    // Java support
    id("java")
    // Kotlin support
    id("org.jetbrains.kotlin.jvm") version "1.9.23"
    // gradle-intellij-plugin - read more: https://github.com/JetBrains/gradle-intellij-plugin
    id("org.jetbrains.intellij") version "1.17.2"
}


group = "com.wsl.symlinks"
version = "2024.2.0"

// Configure project's dependencies
repositories {
    mavenCentral()
}
dependencies {
    testImplementation("org.jetbrains.kotlin:kotlin-test")
    testImplementation("org.assertj:assertj-core:3.25.3")
    testImplementation("org.junit.platform:junit-platform-launcher:1.10.2")
    implementation(kotlin("test"))
    implementation("org.codehaus.jettison:jettison:1.5.4")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
}

// Configure gradle-intellij-plugin plugin.
// Read more: https://github.com/JetBrains/gradle-intellij-plugin
intellij {
    pluginName.set("WslSymlinks.js")

    // see https://www.jetbrains.com/intellij-repository/releases/
    // and https://www.jetbrains.com/intellij-repository/snapshots/
    version.set("242.16677.21-EAP-SNAPSHOT")
    type.set("IU")

    downloadSources.set(!System.getenv().containsKey("CI"))
    updateSinceUntilBuild.set(true)

    // Plugin Dependencies -> https://plugins.jetbrains.com/docs/intellij/plugin-dependencies.html
    // Example: platformPlugins = com.intellij.java, com.jetbrains.php:203.4449.22
    //
    // com.dmarcotte.handlebars: see https://plugins.jetbrains.com/plugin/6884-handlebars-mustache/versions
    plugins.set(listOf())

    sandboxDir.set(project.rootDir.canonicalPath + "/.sandbox")
}

tasks {
    compileKotlin {
        kotlinOptions.jvmTarget = "17"
    }

    compileTestKotlin {
        kotlinOptions.jvmTarget = "17"
    }

    publishPlugin {
        token.set(System.getenv("ORG_GRADLE_PROJECT_intellijPublishToken"))
    }

}


tasks.buildSearchableOptions {
    enabled = false
}


tasks.register("printVersion") { println(version) }


tasks.register("updateChangelog") {
    var input = generateSequence(::readLine).joinToString("\n")
    input = input.replace(":rocket:", "🚀")
    input = input.replace(":bug:", "🐛")
    input = input.replace(":documentation:", "📝")
    input = input.replace(":breaking:", "💥")
    input += "\nsee <a href=\"https://github.com/patricklx/intellij-wsl-symlinks/blob/main/CHANGELOG.md\">https://github.com/patricklx/intellij-wsl-symlinks/</a> for more"
    val f = File("./src/main/resources/META-INF/plugin.xml")
    var content = f.readText()
    content = content.replace("CHANGELOG_PLACEHOLDER", input)
    f.writeText(content)
}

tasks.register("listRecentReleased") {
    val text = URL("https://plugins.jetbrains.com/api/plugins/24097/updates?channel=&size=8").readText()
    val obj = groovy.json.JsonSlurper().parseText(text)
    val versions = (obj as ArrayList<Map<*,*>>).map { it.get("version") }
    println(groovy.json.JsonBuilder(versions).toPrettyString())
}

tasks.register("verifyAlreadyReleased") {
    var input = generateSequence(::readLine).joinToString("\n")
    val text = URL("https://plugins.jetbrains.com/api/plugins/24097/updates?channel=&size=100").readText()
    val obj = groovy.json.JsonSlurper().parseText(text)
    val versions = (obj as ArrayList<Map<*,*>>).map { it.get("version") }
    println(versions.contains(input))
}
