# 配布用ビルド

`tools/package_release.py --source-ref HEAD`を使います。release variantのビルド、単体テスト、lint、依存ライセンス、デバッグ・テスト画面の不混入、zipalign、配布鍵の署名を確認し、APK・ソースZIP・検証情報・SHA-256を生成します。

署名鍵はGit管理外の`.signing/release.keystore`、パスワードは`.signing/password`、aliasは`galaxycharge-release`です。ディレクトリは0700、秘密ファイルは0600にします。更新では同じ鍵を使います。公開証明書のSHA-256は`release-certificate.sha256`に保存します。

署名鍵とパスワードをGit、配布ZIP、ログに含めません。リリース本文は利用者向けに、機能、対応環境、導入操作、取得できない項目を説明します。CI用の未署名APKを配布しません。既存タグを移動しません。
