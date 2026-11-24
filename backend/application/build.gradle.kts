plugins { alias(libs.plugins.serialization) }
dependencies { api(project(":backend:domain")); api(project(":backend:engine-api")) }
sourceSets.main { resources.srcDir(rootProject.file("prompts")) }
