# 進捗メモ（引き継ぎ用）

最終更新：2026-10-31（夜間の自動作業分）

## いまの状態

- 仕様：`docs/SPEC.md` v0.4 で確定。
- 実装：**M1 のコードを書き終えた**段階。Android SDK がこのサンドボックスから取得できないため、
  Android 本体のコンパイルは GitHub Actions 上で確認している（下記「ビルドの見方」）。
- ゲームロジック（`:core`）はローカルで単体テスト済み（32 本グリーン）。

## モジュール構成

| モジュール | 中身 | テスト |
|---|---|---|
| `core/` | 純 Kotlin。ルール計算：体力、獲得量、ストリーク、3 択生成、週ランク、中期目標の逆算。プリセット。 | `./gradlew :core:test`（Android SDK 不要。`CORE_ONLY=1` を付けると `:app` を読まない） |
| `app/` | Android。Room / DataStore / Hilt / Compose / AlarmManager。 | `./gradlew :app:assembleDebug`（SDK 必要） |

主要ファイル：

- `core/src/main/kotlin/dev/kunoshoma/produce/core/rules/*` — SPEC 3 章・12 章の数式はここ。
- `app/src/main/java/dev/kunoshoma/produce/domain/ProduceRepository.kt` — ゲーム進行の書き込みはすべてここを通す。
- `app/src/main/java/dev/kunoshoma/produce/data/db/Entities.kt` — Room のテーブル定義。
- `app/src/main/java/dev/kunoshoma/produce/ui/*` — 画面（Home / Produce / Lesson / Result / Actions / Manual / Settings）。

## M1 でできること

- ホーム：3 軸ゲージ、体力バー、調子（好調/絶好調）、連続日数、週のお題、今週のランクと合計、今日の記録一覧。
- プロデュース開始：3 択（レッスン 2 枚 + 休む）。SP / お題バッジ。引き直し（1 日 2 回）。体力 0 なら「休む / 寝る」のみ。
- レッスン：タイマー。終了時に通知。中断も可（経過比例・最低 50%）。
- 自己申告：ちゃんとできた / 微妙 / 失敗。理由チップ。正直ボーナス。隠れて YouTube → 娯楽ログに自動追加。
- 休む：30 / 60 分（設定可）。終了時に通知。実時間で体力回復（30 以下 1.5 倍、80 以上は回復なし、1 日 +30 まで）。
- 後付け記録：「さっきやった」「娯楽してた」。前日分まで。
- 寝る宣言 / オフ日 / 起床時の睡眠申告（回復率に反映）。
- アクション管理：追加・編集・ON/OFF・論理削除。時間を選ぶとコスト・獲得の目安が自動入力。
- 設定：体力まわり、休む候補、引き直し回数、SP 率、日付境界、ランク閾値、軸の名前。
- 通信なし（`INTERNET` 権限なし）、バックアップ無効。

## まだないもの（SPEC との差分）

- M2：娯楽アプリ検知の割り込み（AccessibilityService）、静かな時間、死活監視。
- M3：日次レポートの自動生成・通知、グラフ、ウィジェット、JSON バックアップ/復元。
- M4：週次レポート画面（ランク計算自体は入っている）。
- M4.5：中期目標の UI（逆算ロジックは `GoalCalculator` にあり、テスト済み）。

## ビルドの見方

1. GitHub → Actions → "Android CI"。ブランチ `claude/idol-master-self-produce-app-zpkjs3` の最新 run を開く。
2. 成功していれば Artifacts に `produce-m-iself-debug-apk` がある。ダウンロードして Pixel に入れる（開発者オプションで USB デバッグ or ファイルから直接インストール）。
3. 手元で開くなら Android Studio でルートを開くだけ。JDK 17 以上。

## 設計上の判断（SPEC に書いていない細部）

- 体力の現在値は `day_states.stamina` に持つ。ログを削除すると消費分を戻す。
- 「今日」は 30 秒ごとに再評価し、日付境界をまたぐとホームが自動で切り替わる。
- 調子（好調/絶好調）は「昨日までの連続日数 + 1」で判定して今日のレッスンに適用する。
- 週のお題は週の最初に `Random(weekStart)` で決める（中期目標が入ったら遅れ軸を優先する予定）。
- 休むの回復は終了時に実時間で計算（許可時間が上限）。アプリを開き直さないと終了にならないので、M2 で割り込み側から自動終了させる。
