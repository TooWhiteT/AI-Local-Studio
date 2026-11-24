plugins { alias(libs.plugins.serialization) }
dependencies { api(project(":backend:core")); api(libs.coroutines) }
