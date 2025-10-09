import java.io.File

gradle.settingsEvaluated {
    val props = File(rootDir, "local.properties")
    if (!props.exists() || !props.readText().contains("sdk.dir=")) {
        val env = System.getenv("ANDROID_SDK_ROOT") ?: System.getenv("ANDROID_HOME")
        val defaultMac = File(System.getProperty("user.home"), "Library/Android/sdk").absolutePath
        val sdk = env ?: defaultMac
        props.appendText("\nsdk.dir=$sdk\n")
        println(">> Set sdk.dir=$sdk")
    }
}
