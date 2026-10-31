// ルートはモジュールの集約のみ。Kotlin 系プラグインはここで一度だけクラスパスに載せ（apply false）、
// 各モジュールで適用する。AGP / KSP / Hilt は :app だけが使うので :app 側で宣言する
// （Android SDK が無い環境でも CORE_ONLY=1 で :core を単独ビルドできるようにするため）。
plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
