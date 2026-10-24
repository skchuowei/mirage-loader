plugins {
    java
    application
}

group = "io.mirage"
version = "0.4.0"

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

dependencies {
    implementation(project(":launcher:mirage-launch"))
    implementation(project(":core:mirage-loader"))
    implementation(project(":core:mirage-mixin"))
    implementation(project(":core:mirage-eventbus"))
    implementation(project(":api:mirage-api"))
}

application {
    mainClass.set("io.mirage.ls.LifecycleStarter")
}