# 扫描文档与提取文字接入计划

2026-10-28。只读调查：未下载 OCR AAR／模型，未操作手机，未修改构建或 Manifest。当前 OCR **尚未接入和验收**。

## 结论

可落地的默认方向是 **ML Kit 随应用捆绑的中文识别模型**，同一个中文识别器已经支持中文及 Latin 字母，不必为了英文再捆绑一套 Latin 模型。[ChineseTextRecognizerOptions 官方说明](https://developers.google.com/android/reference/com/google/mlkit/vision/text/chinese/ChineseTextRecognizerOptions)

现有 minSdk 24 满足当前指南正文的 API 23 最低要求。指南顶部自动摘要仍写 API 21，与正文不一致；本项目按更严格的正文处理。捆绑模型可立即使用；动态模型需由 Google Play services 下载，不能作为“首次安装完全离线可用”的保证。[Android OCR 指南](https://developers.google.com/ml-kit/vision/text-recognition/v2/android)

不能直接把 ColorOS 便签的 OCR 页面当成第三方公共接口。也不能把拍照／插入图片入口标为已完成的“文档扫描”或“提取文字”。

## 本机与原 APK 证据

- `dpc/libs/` 当前只有 `pdfbox-android-2.0.27.0.jar` 和 README。限定扫描 `~/.gradle/caches` 的文件路径，没有发现 ML Kit、OCR、Tesseract、OpenCV 的已缓存 AAR/JAR；不证明整台机器绝对不存在相关软件。
- 只读包 `.codex-fix/note-rea/note.apk` SHA256：`df49d7291c537e38934cb758169889a25b265818057cf8c651b7cc2c7a62b2e5`。用 SDK `aapt dump xmltree ... AndroidManifest.xml` 检查，保存为 `.codex-fix/note-rea/whole-app/ocr-manifest.txt`。这部分是普通静态工具证据，不冒称新的 REA Evidence。
- 包的 `<queries>` 中有 `com.coloros.ocrscanner`、`com.coloros.ocrservice`。
- `com.nearme.note.ocr.OcrConverterActivity` 明确 `exported=false`，并标有 `oppo.permission.OPPO_COMPONENT_SAFE`；`SuperTextActivity` 和 `EnhancePicActivity` 也为非导出。不能直接启动这些私有组件。
- 对各 `classes*.dex` 字符串表作限定搜索，可见候选动作 `coloros.intent.action.DOCUMENT_SCANNER`、`HQ_DOC_SCANNER`、`SCANNER_MAIN_PAGE`、`VIEW_OCR`，以及 OCR service/provider 名称。这只说明字符串出现在包里；没有反编译控制流、调用参数、结果键或外部 scanner 包的导出声明，**不能据此确认公开可调用或取得 OCR 结果**。
- 暂无已证实的通用 Android／ColorOS 公共 OCR Intent 契约。以后手机接回可先查询 resolver，再以独立临时图片验证；不能尝试绕过非导出或签名权限。

## 确切依赖与体积

建议锁定一个入口依赖：

```text
com.google.mlkit:text-recognition-chinese:16.0.1
```

官方示例也使用此 bundled 版本。官方指南给出的 APK 增量估计是每脚本、每架构约 4 MB；这是增量估计，不等于单个 AAR 大小。[官方示例依赖](https://github.com/googlesamples/mlkit/blob/master/android/vision-quickstart/app/build.gradle)，[OCR 安装选项](https://developers.google.com/ml-kit/vision/text-recognition/v2/android)

本轮只 GET 小型 POM，并对 AAR 发 HEAD，没有下载二进制：中文入口 AAR `Content-Length=2,036,186` 字节；作为对照的 Latin 入口 AAR为 `1,383,148` 字节。未计算完整传递依赖及每个 ABI 的 APK 增量，不能把上述数字当成最终安装体积。

中文入口 [Google Maven POM](https://dl.google.com/dl/android/maven2/com/google/mlkit/text-recognition-chinese/16.0.1/text-recognition-chinese-16.0.1.pom) 的直接依赖：

| 坐标 | 版本 |
| --- | --- |
| com.google.android.gms:play-services-base | 18.5.0 |
| com.google.android.gms:play-services-basement | 18.4.0 |
| com.google.android.gms:play-services-mlkit-text-recognition-chinese | 16.0.1 |
| com.google.android.gms:play-services-mlkit-text-recognition-common | 19.1.0 |
| com.google.mlkit:common | 18.11.0 |
| com.google.mlkit:text-recognition-bundled-common | 17.0.0 |

已经进一步检查的小型 POM说明：

- [bundled-common 17.0.0](https://dl.google.com/dl/android/maven2/com/google/mlkit/text-recognition-bundled-common/17.0.0/text-recognition-bundled-common-17.0.0.pom) 还依赖 `play-services-tasks:18.2.0`、`vision-common:17.3.0` 等。
- [text-recognition-common 19.1.0](https://dl.google.com/dl/android/maven2/com/google/android/gms/play-services-mlkit-text-recognition-common/19.1.0/play-services-mlkit-text-recognition-common-19.1.0.pom) 还包含 `vision-interfaces:16.3.0`、ODML image、Firebase components／encoders，以及 DataTransport。
- [ML Kit common 18.11.0](https://dl.google.com/dl/android/maven2/com/google/mlkit/common/18.11.0/common-18.11.0.pom) 依赖 `androidx.appcompat:appcompat:1.6.1`、`androidx.core:core:1.0.0` 等。完整闭包需要正式依赖解析，不能只下载入口 AAR 或照此表手工漏包。已取 POM保存在忽略目录 `whole-app/ocr-poms/`。

入口虽然含 `com.google.android.gms` 类库依赖，但这不等同于“模型必须在线下载”；是否完全不依赖已安装 Play services，仍须在无 GMS 的目标机上验证，不拿类库名称推断运行结果。

## Java 业务接口

官方 API 的最小路径如下；这是计划代码，不是本项目已编译的实现：

```java
TextRecognizer recognizer = TextRecognition.getClient(
        new ChineseTextRecognizerOptions.Builder().build());
InputImage image = InputImage.fromFilePath(context, imageUri);
recognizer.process(image)
        .addOnSuccessListener(result -> showEditableResult(result.getText()))
        .addOnFailureListener(error -> showRetry(error))
        .addOnCompleteListener(task -> recognizer.close());
```

所需公开类型为 `com.google.mlkit.vision.text.TextRecognizer`、`TextRecognition`、`Text`、`com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions`、`com.google.mlkit.vision.common.InputImage` 和 `com.google.android.gms.tasks.Task`。`getText()` 之外还可取得块、行、字符边界。[官方接口和处理流程](https://developers.google.com/ml-kit/vision/text-recognition/v2/android)

产品层新增 `MemoOcr`，只消费用户选择／拍摄的图片 URI，识别结果进入可编辑确认页，用户点“插入”后才加入当前草稿，加入撤销历史；不自动覆盖原笔记。处理取消、页面退出、旧请求回调、超大图、旋转、无文字和识别失败，保留原图。中文与英文混排必须用同一套识别器验收。

## 当前手工构建需要补的步骤

以下是对现有 `dpc/build.sh` 的工程推导，尚未实编译验证，不是“改一条 classpath 就能用”：

1. 在隔离依赖准备模块中解析 Google Maven 依赖闭包，锁定最终选中版本、来源 URL 和 SHA256；保存可离线重建的依赖清单。优先用单独的 Gradle 准备工程完成解析／Manifest 合并，再给现有手工构建消费，避免整个应用随意迁移架构。
2. 提取每个 AAR 的 `classes.jar`／嵌入 JAR，加入 javac 和 d8。现有脚本只加入 PDFBox JAR，完全不会自动加载 OCR 传递依赖。
3. 合并全部 AAR Manifest、组件发现 metadata 和 `${applicationId}`；保留 ML Kit 初始化及组件注册逻辑。只拷贝 `classes.jar` 会漏掉初始化信息。官方提供 `MlKitInitProvider` 自动初始化或显式 `MlKit.initialize(Context)` 的说明，具体选择须依赖完整组件发现配置。[MlKit 初始化说明](https://developers.google.com/android/reference/com/google/mlkit/common/MlKit)
4. 将 AAR 的 `res/` 编译并交给 aapt2 链接，生成所有库包名需要的 R 类／资源 ID；不能只编译本项目 res。整理 assets、Java resources 和 `jni/<abi>/*.so`，包内使用正确的 `assets/`、`lib/<abi>/` 路径。AAR 包含资源／Manifest／原生库，不能按普通 JAR 对待。[Android AAR 官方结构说明](https://developer.android.com/studio/projects/android-library)
5. 核对同名资源冲突、provider authority 唯一性、重复 classes、consumer ProGuard（如启用压缩）、原生 ABI及 Android 15+ 16 KB 页兼容。不得从 AAR丢弃看似无用的服务注册／模型资源。
6. 以 min24、compile34 实际构建，分别检查 arm64 真机和 x86_64 模拟器；逐个 AAR 的 minCompileSdk／资源属性是否超出 34 尚未检查，不能提前承诺 compile34 一定通过。
7. 回归完全离线首次使用、无 GMS、API24／34／36、旋转、多页、简繁中文／英文混排、删除／恢复及 ZIP 附件备份。加密／私密笔记不得在 OCR 完成前后自动落明文临时文件到外部目录。

## 文档扫描是另一层能力

OCR 提取文字不自动提供找边、透视矫正、去阴影、多页 PDF。Google 的独立 Document Scanner SDK 是 `com.google.android.gms:play-services-mlkit-document-scanner:16.0.0`，API21+、至少 1.7 GB RAM，模型与扫描 UI由 Google Play services 下载，应用增量约 300 KB；它不能保证本项目在无 GMS／首次断网时的扫描可用性。[Document Scanner 官方指南](https://developers.google.com/ml-kit/vision/doc-scanner/android)

因此推荐先接 bundled OCR；扫描页面独立做原图保留、手动裁切／旋转、分页管理与已有 PDFBox 导出。自动找边和透视矫正需实际实现并验收后才标为支持。Google Scanner 可以作为有 GMS 的可选实现，不应是唯一通路。

## 未完成项与决策点

- 未下载完整依赖、未合并 AAR／Manifest、未编译 OCR、未识别任何图片。**本轮仅交付研究与接入计划。**
- 未确认 ColorOS 外部 scanner 的导出权限、参数和返回契约；不靠私有组件绕过。
- ML Kit 完整 APK 增量、ABI 支持、compile34 兼容和无 GMS 真机运行待测。
- 若项目继续要求“完全不新增依赖／不改手工打包”，则无法在当前代码里提供可靠通用中英文 OCR；需要明确接受依赖接入工作，或另选独立离线 OCR 引擎。拍照后打开图片不是已完成 OCR。
