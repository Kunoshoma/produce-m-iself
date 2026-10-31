// ルートはモジュールの集約のみ。プラグインは各モジュールで適用する。
tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
