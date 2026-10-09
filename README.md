# jasperreports プラグイン

Apache OFBiz 向けの JasperReports ビューハンドラプラグイン。  
OFBiz のビュー機構に `type="jasperreports"` を追加し、`.jrxml` テンプレートから PDF / CSV / XML レポートを生成する汎用基盤を提供する。

---

## 概要

このプラグイン自体はビジネスロジックを持たない。  
他のプラグインが `.jrxml` テンプレートと view-map を追加するだけで、JasperReports によるレポート出力できるようにする。

- マウントポイント: `/jasperreports`
- ベース権限: `OFBTOOLS`
- 対応出力形式: PDF（デフォルト）、CSV、XML

---

## ディレクトリ構造

```
plugins/jasperreports/
├── build.gradle                          # 依存ライブラリ定義
├── ofbiz-component.xml                   # コンポーネント登録・セキュリティ・webapp定義
├── config/
│   ├── JasperReportsUiLabels.xml         # UI表示ラベル（en / fr / ja）
│   └── JasperReportsErrorUiLabels.xml    # エラーメッセージラベル（en / fr / ja）
├── data/
│   ├── JasperReportsSecurityPermissionSeedData.xml   # seed: 権限定義
│   └── JasperReportsSecurityGroupDemoData.xml        # demo: セキュリティグループ
├── src/main/java/org/apache/ofbiz/jasperreports/webapp/view/
│   ├── JasperReportsViewHandler.java     # OFBiz ビューハンドラ（メインエンジン）
│   └── JasperReportsExporter.java        # 出力フォーマット変換（PDF / CSV / XML）
├── webapp/jasperreports/
│   ├── index.jsp                         # /jasperreports/ → /control/main リダイレクト
│   ├── reports/
│   │   └── SampleReport.jrxml           # 動作確認用サンプルテンプレート
│   └── WEB-INF/
│       ├── web.xml                       # Webアプリ設定
│       └── controller.xml               # リクエストマッピング・ビューハンドラ登録
└── widget/
    ├── JasperReportsScreens.xml          # OFBiz スクリーン定義
    └── JasperReportsMenus.xml            # アプリケーションメニュー定義
```

---

## 依存ライブラリ

`build.gradle` で `pluginLibsCompile` として追加される。

| ライブラリ | バージョン | 用途 |
|---|---|---|
| `net.sf.jasperreports:jasperreports` | 7.0.8 | JasperReports コアエンジン |
| `net.sf.jasperreports:jasperreports-pdf` | 7.0.8 | PDF エクスポーター（JR 7.x から分離） |
| `com.github.librepdf:openpdf-fonts-extra` | 1.3.43 | CJK フォント（HeiseiKakuGo-W5 等） |

Spring / Hibernate / Chrome DevTools Protocol は除外済み。

---

## アーキテクチャ

```
[ブラウザ]
    |
    | GET /jasperreports/control/RunReport?jrTemplate=component://xxx/reports/Foo.jrxml
    v
[ControlServlet]
    |
    v
[JasperReportsViewHandler.render()]
    |
    +-- jrDataSource 属性あり -------> JasperFillManager（JRDataSource 経由）
    |
    +-- jrDataSource 属性なし -------> JDBC 接続取得 -> JasperFillManager（SQL 実行）
    |
    v
[JasperReportsExporter.export()]
    |
    +-- application/pdf --> JasperExportManager（PDF バイナリ）
    +-- text/csv        --> JRCsvExporter（CSV テキスト）
    +-- text/xml        --> JRXmlExporter（XML テキスト）
    |
    v
[HTTP Response]
```

### `JasperReportsViewHandler`

`AbstractViewHandler` を継承するメインエンジン。`render()` の処理フロー:

1. **テンプレートロード** — `jrTemplate` パラメータまたは view-map の `page` 属性でロケーション決定。
   - `.jrxml` → `JasperCompileManager.compileReport()` でオンザフライコンパイル
   - `.jasper` → 事前コンパイル済みオブジェクトを直接ロード
   - `component://` ロケーションは OFBiz `FlexibleLocation` で解決
2. **パラメータ構築** — リクエストパラメータを基底として `jrParameters` 属性でマージ上書き
3. **データソース判定** — `jrDataSource` 属性があれば entity データ、なければ JDBC 接続
4. **フォーマット決定** — `jrContentType` パラメータまたは view-map の `content-type`（デフォルト: `application/pdf`）
5. **出力** — `jrOutputFileName` があれば `Content-Disposition: attachment` ヘッダを付与

### `JasperReportsExporter`

package-private の final ユーティリティクラス。content-type に応じてエクスポーターを切り替える。

---

## リクエスト属性・パラメータ一覧

| 種別 | 名前 | 型 | 説明 |
|---|---|---|---|
| request 属性 | `jrDataSource` | `JRDataSource` | 上流イベントが設定するデータソース |
| request 属性 | `jrParameters` | `Map<String, Object>` | レポートパラメータ（リクエストパラメータを上書き） |
| リクエストパラメータ | `jrContentType` | `String` | 出力フォーマット上書き |
| リクエストパラメータ | `jrTemplate` | `String` | テンプレートロケーション上書き |
| リクエストパラメータ | `jrOutputFileName` | `String` | ダウンロード時のファイル名 |

---

## セキュリティ

### 権限（seed）

| 権限 ID | 説明 |
|---|---|
| `JASPERREPORTS_ADMIN` | 全操作 |
| `JASPERREPORTS_VIEW` | 閲覧・実行のみ |

`SUPER` グループには両権限が付与済み。

### セキュリティグループ（demo）

| グループ ID | 付与権限 |
|---|---|
| `JASPERREPORTSADMIN` | `JASPERREPORTS_ADMIN` + `JASPERREPORTS_VIEW` + `OFBTOOLS_VIEW` |
| `JASPERREPORTSUSER` | `JASPERREPORTS_VIEW` + `OFBTOOLS_VIEW` |

---

## サンプルレポート

`/jasperreports/control/SampleReport` にアクセスすると動作確認用レポートが PDF で出力される。  
クエリパラメータ `?reportTitle=タイトル文字列` でタイトルを上書きできる。

CSV 出力: `/jasperreports/control/SampleReport?jrContentType=text/csv`  
XML 出力: `/jasperreports/control/SampleReport?jrContentType=text/xml`

---

## 他プラグインからの利用方法

### 1. ビューハンドラ登録

`webapp/WEB-INF/controller.xml` にハンドラを追加する。

```xml
<handler name="jasperreports" type="view"
    class="org.apache.ofbiz.jasperreports.webapp.view.JasperReportsViewHandler"/>
```

### 2. view-map の定義

```xml
<!-- テンプレートを固定する場合 -->
<view-map name="MyReport" type="jasperreports"
    page="component://myplugin/webapp/myplugin/reports/MyReport.jrxml"
    content-type="application/pdf"/>

<!-- テンプレートをリクエストパラメータで指定させる場合 -->
<view-map name="RunReport" type="jasperreports" content-type="application/pdf"/>
```

### 3. Groovy イベントでのデータ準備

entity データを使う場合は `JRDataSource` を request 属性にセットする。

```groovy
import net.sf.jasperreports.engine.data.JRMapCollectionDataSource

// ヘッダパラメータ
request.setAttribute("jrParameters", [
    reportTitle: "月次レポート",
    generatedBy : userLogin.userLoginId,
])

// 明細行データ
def rows = delegator.findList("MyEntity", null, null, ["sequenceNum"], null, false)
request.setAttribute("jrDataSource", new JRMapCollectionDataSource(rows.collect { it.getAllFields() }))
```

`jrDataSource` をセットしない場合、ビューハンドラが entity engine の JDBC 接続を使って `.jrxml` 内の SQL を直接実行する。

### 4. `.jrxml` テンプレートの配置

```
myplugin/webapp/myplugin/reports/MyReport.jrxml
```

日本語を出力する場合は `HeiseiKakuGo-W5` フォントを指定する（`openpdf-fonts-extra` に含まれる）。

```xml
<textElement>
    <font fontName="HeiseiKakuGo-W5" size="10" isPdfEmbedded="true"
          pdfFontName="HeiseiKakuGo-W5" pdfEncoding="UniJIS-UCS2-H"/>
</textElement>
```

---

## 動作確認

```powershell
# コンパイル確認
.\gradlew.bat compileJava compileGroovy

# demo データをロードして起動後、ブラウザで確認
# https://localhost:8443/jasperreports/control/SampleReport
```
