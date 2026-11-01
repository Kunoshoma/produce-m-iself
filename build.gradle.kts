// ルートはモジュールの集約のみ。プラグインは各モジュールで適用する。
//
// 注意：Kotlin プラグインをここで apply false 宣言すると、AGP が :app 側のクラスローダにしかいないため
// kotlin-android が com.android.build.gradle.BaseExtension を見つけられず落ちる。
// AGP をここに置くと、Android SDK を取得できない環境で CORE_ONLY=1 の :core 単独ビルドができなくなる。
// そのため「Kotlin プラグインが複数のサブプロジェクトで読み込まれた」という警告は許容する。
tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
