# 開発者向けガイド

Android SDK 37、JDK 21、Gradle wrapperを使います。Kotlinで実装しています。

```sh
./gradlew :app:testDebugUnitTest :app:lintRelease :app:assembleRelease :app:writeRuntimeDependencyList
python3 tools/legal_catalog.py --check
```

依存ライブラリを変更したときは、解決済みのruntime dependency一覧を更新し、`python3 tools/legal_catalog.py`でライセンスと埋込NOTICEを再生成して確認します。

## 読み取りの境界

`ReadCommands`が固定した2種類のシェルスクリプトだけを持ちます。ユーザー入力や読取値をコマンドへ渡すAPIはありません。`RootReader`は15秒のタイムアウト、出力の上限、画面離脱時の終了処理を持ちます。常駐Rootデーモン、書込用インターフェース、Xposedフック、インターネット通信はありません。

sysfsの電流と電圧、ドライバーの投票、デバイスツリーの設定は別々に保持します。負の制御結果や欠落を実測0と扱いません。生のu32と存在フラグを区別し、文字列を推測してビットマスクを壊さないようにします。

設定表はファームウェアのfingerprintとアプリ版をキーに、このアプリのfilesDirへ保存します。初回取得後はキャッシュを使い、必要なときに利用者が再取得します。失敗した初回探索を毎回のサンプルで繰り返しません。同梱するのは項目の説明だけで、採取済みの実機値ではありません。

## 検証範囲

JVMテストはフレーム切断・Root拒否・符号・バイト順・数値0と存在フラグ・LRP表の復帰温度・投票結果・固定読取コマンドを対象にします。エミュレーターの基本表示と、物理Galaxyの保護された値の取得は別に記録します。実機の充電器入力W数を測定したとは表記しません。生ログや署名鍵をGitへ追加しないでください。

配布は[署名・リリース手順](releasing.md)に従います。READMEには利用者向けの導入・制限を書き、内部の検証経緯はこのディレクトリへ分けます。
