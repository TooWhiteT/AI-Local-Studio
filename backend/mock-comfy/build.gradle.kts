plugins { application }
dependencies { implementation(project(":backend:core")); implementation(libs.netty); implementation(libs.server.ws); runtimeOnly(libs.log) }
application { mainClass.set("studio.mock.MockServerKt") }
tasks.named<JavaExec>("run") { workingDir = rootDir }
