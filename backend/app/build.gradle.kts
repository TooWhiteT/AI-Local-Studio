plugins { application; alias(libs.plugins.serialization) }
dependencies {
    implementation(project(":backend:infrastructure"))
    implementation(project(":backend:scheduler"))
    implementation(project(":backend:engine-comfy"))
    implementation(project(":backend:engine-llm"))
    implementation(project(":backend:engine-tts"))
    implementation(project(":backend:engine-media"))
    implementation(libs.netty)
    implementation(libs.negotiation)
    implementation(libs.server.json)
    implementation(libs.status)
    implementation(libs.partial)
    implementation(libs.body.limit)
    runtimeOnly(libs.log)
    testImplementation(project(":backend:mock-comfy"))
}
application { mainClass.set("studio.app.MainKt") }
tasks.named<JavaExec>("run") { workingDir = rootDir }
// Wire tests simulate a GPU in their own processes and must not acquire the running Studio's device lock.
tasks.named<Test>("test") {
    val temporary = layout.buildDirectory.dir("tmp/studio-integration").get().asFile
    systemProperty("java.io.tmpdir", temporary.absolutePath)
    doFirst { temporary.mkdirs() }
}
