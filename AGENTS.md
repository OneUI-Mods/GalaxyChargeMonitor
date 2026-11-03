# 配布とリリース

- ユーザーへ添付するAPK・GitHub ReleaseのAPKは必ずrelease variantでビルドし、署名を検証する。debug APKや検証用Activityを含むAPKを配布しない。
- `tools/package_release.py`と`docs/releasing.md`の手順を使う。署名鍵・パスワードをGit、配布ファイル、ログへ含めない。
- リリース文とタイトルは外部の利用者向けに書く。変更による使い勝手、対応環境、必要な更新操作、既知の制限を記載する。ユーザー個人の設定値、内部クラス名、hook件数、テスト作業の経緯、CI作業報告をリリース文に含めない。
- 実装詳細・試験記録は開発用ドキュメントへ分ける。既存タグを移動してソース履歴を変更しない。

# GitHub管理

- 既存アプリと同じ非公開リポジトリで管理し、mainへのpushとPull Requestで自動検証する。
- CIはテスト・lint・releaseビルド・依存ライセンス・APK構造を確認する。CIへ署名鍵を渡さず、自動でReleaseを作成しない。
- 配布時は署名済みrelease APK、対応コミットのソースZIP、SHA256SUMS、署名検証結果を添付する。CIの未署名APKは配布しない。
