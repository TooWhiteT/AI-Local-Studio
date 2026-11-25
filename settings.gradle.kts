pluginManagement { repositories { gradlePluginPortal(); mavenCentral() } }
dependencyResolutionManagement { repositories { mavenCentral() } }
rootProject.name = "local-ai-studio"
include("backend:core", "backend:domain", "backend:engine-api", "backend:application",
    "backend:infrastructure", "backend:engine-comfy", "backend:engine-llm", "backend:engine-tts", "backend:engine-media", "backend:scheduler", "backend:mock-comfy", "backend:app")
