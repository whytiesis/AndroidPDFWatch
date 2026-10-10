# 纸阅

原生 Android 本地 PDF / Word 阅读、批注与正文编辑应用。当前版本 **1.6.1**，包名 `cn.paperpdf.reader`，支持 Android 6.0 及以上。

## 下载

[下载纸阅 1.6.1 APK](https://github.com/whytiesis/AndroidPDFWatch/releases/download/v1.6.1/PaperPDF-1.6.1.apk) · [发行说明](https://github.com/whytiesis/AndroidPDFWatch/releases/tag/v1.6.1) · [仓库备用安装包](release-assets/PaperPDF-1.6.1.apk)

旧版发行：[纸阅 1.6.0](https://github.com/whytiesis/AndroidPDFWatch/releases/tag/v1.6.0)、[纸阅 PDF 1.5.0](https://github.com/whytiesis/AndroidPDFWatch/releases/tag/v1.5.0)。

[查看全部历史版本](https://github.com/whytiesis/AndroidPDFWatch/releases)

## 1.6.1 更新

- 修复大 PDF 打开时因数据流缓存耗尽内存的问题；打开、搜索和导出采用有限内存与磁盘缓存。
- 保留 PDF / Word 阅读、批注、DOCX 正文与表格文字编辑、独立草稿及保存能力。
- 34 项专用模拟器集成测试通过，含 600 MiB 合成 PDF 打开、渲染、翻页、搜索和导出；沿用原正式版签名，可覆盖升级。

安装包大小：11,967,754 bytes。SHA-256：`3279582A1473F91E2D96D0EE57C8237BB07C3A87365A0899FF4D20AD5DFD637F`。历史正式安装包的校验值见 [SHA256SUMS](release-assets/SHA256SUMS.txt)。

## 安装与使用

当前版本为 1.6.1。下载后在安卓手机上打开 APK，并按系统提示允许该来源安装应用。支持 Android 6.0（API 23）及以上；沿用原发布签名，可覆盖安装 1.6.0 及以前版本。

PDF 不设固定文件大小上限。1.6.1 为打开、搜索和导出启用有限内存与磁盘缓存，修复大文件解析时的内存不足；已使用含真实 PDF 数据流的 600 MiB 合成文件验证。读取大文件需要足够的本地存储，单页复杂图像和文档结构仍可能受到设备资源限制。

1. 打开纸阅，点击“打开手机中的文档”。应用会列出设置中已授权目录内的 PDF、DOCX 和 DOC，可搜索文件名并直接打开；点击“从文件中选择”可从系统文件选择器选取其他 PDF 或 Word。也可在文件管理器中使用“打开方式”或“分享”选择纸阅。
2. 微信或 QQ 已下载的 PDF：进入首页右上角“设置”，点击“添加微信 / QQ 文件夹”授权一次，以后可在“打开手机中的文档”中直接浏览、搜索这些 PDF；也可以在微信或 QQ 中点击文件的“用其他应用打开”或“分享”，选择纸阅，再点“保存到纸阅并打开”建立稳定的本地副本。
3. 默认单页阅读支持双指缩放、拖动、双击放大；左右滑动或底部按钮翻页，点击页码跳转。开启“连续滚动阅读”后也支持双指缩放（1–5 倍）、双击放大/还原和拖动，默认收起左右白边使正文适宽，可在设置关闭。适宽只改变显示，不修改文件、不重排 PDF 段落；无法可靠判断白边时显示完整页宽。
4. 阅读模式轻触 PDF 隐藏/显示工具栏和系统栏；隐藏时按返回先呼出工具栏。双击仍缩放；编辑时的点击不会隐藏 UI。横竖屏跟随系统自动旋转设置，横屏单页适合页宽，长页上下拖动阅读；工具栏在上下方，侧边导航栏和挖孔区域保留安全边距。画笔可手写圈画，荧光工具拖出矩形标记，文字工具点击页面后输入中文或多行批注；擦除工具点按删除，选择工具可改文字、颜色或删除。连续滚动时点“编辑本页”可直接进入当前页，完成后返回原滚动位置。
5. 主页右上角设置管理文件目录、微信 / QQ 文件夹、连续滚动和夜间阅读；阅读页右上角菜单提供同样的阅读切换，以及搜索、页面旋转/删除/排序、重做和分享。底部工具栏横向滑动可找到撤销。
6. 点击保存，选择“覆盖原文件”或“另存为 PDF”。标题下方显示保存进度和结果。覆盖保存需要原文件写入权限；写入前备份并校验结果，失败时尝试恢复，编辑草稿仍保留。通过“保存到纸阅并打开”导入的副本可直接覆盖。
7. 每份 PDF 分别保留阅读位置与编辑草稿，切换文档不会替换其他草稿；最近列表会标出未导出的编辑。文件移动、更换提供方或临时分享 URI 变化后可能被视为新文件。夜间阅读同步上下工具栏、状态栏、导航栏和弹窗颜色。

应用不申请网络或全部文件访问权限；使用 Android Storage Access Framework 的文件授权。最近文件、阅读位置、导入副本和每份文档的编辑草稿都保留在本机。

Android 11 及以上不能授权其他应用的 `Android/data` 私有目录，也不能将整个 Download 根目录作为长期授权文件夹。因此这些位置请使用“从文件中选择”（会优先打开 Download），或在微信 / QQ 内分享/打开文件；应用不会绕过系统访问限制。已授权的其他文件夹会记住，之后可以直接浏览。

## Word 阅读与修改（1.6.0）

- 自动识别 PDF、DOCX、Word 97–2003 DOC；目录列表、系统选择器、外部打开和分享入口均支持 Word。
- Word 复用单页/连续阅读、缩放、夜间、轻触显隐、横屏、搜索、阅读位置、批注、擦除/选择及独立草稿。
- 点击底部“编辑正文”，选择本页段落或表格单元格，输入文字或换行，再点“应用修改”。支持撤销/重做。修改段落采用首个文字样式，其他段落、表格结构、图片和未编辑的 DOCX 部件保留。
- 建议先改正文再添加批注。已有批注或页面调整时正文编辑被阻止，需先撤销这些修改，避免重新分页使批注错位。
- DOCX 可覆盖原文件、另存 DOCX、分享 DOCX，也可导出 PDF。旧 DOC 采用兼容转换，只另存 DOCX/PDF，保留原 DOC。
- 手写、荧光和文字批注可写入 DOCX 的页面浮动图片，同时保存纸阅可再次编辑的批注模型；其他 Word 软件显示为图片。不同排版引擎可能导致位置变化；跨页段落无法稳定锚定时会提示导出 PDF。
- 页面旋转、删除、排序在阅读副本中可操作，并导出 PDF 保留结果；不支持将这些固定页面操作写回 Word。保存界面会提示，不会悄悄丢弃修改。
- Word 采用本地兼容分页，不保证与 Microsoft Word/WPS 完全一致。常见段落、粗斜体、字号、颜色、表格、PNG/JPEG 等图片可预览；复杂公式、合并表格、列表编号、浮动对象、页眉页脚、分栏、修订等可能简化或不显示。原 DOCX 中未修改的部件仍保留在 Word 保存结果中；PDF 仅包含预览内容。
- 密码加密的 Word 和 Word 6/95 暂不支持；DOCX 编辑保护文档只读。单文件 64 MB、解压总量 64 MB、单部件 24 MB、2 万段落、1000 页保护上限；它们不是无限量排版保证。
- 导出 PDF 后，未写回 Word 的修改仍保留为 Word 草稿，避免误认为原 Word 已更新。

## PDF 修改能力的范围

支持添加、选择和擦除文字、手写及矩形荧光标记，旋转、删除、重排页面和撤销/重做。导出时保留原页面内容，将新增批注写入页面内容；批注导出后无法作为独立批注对象在其他软件中再次编辑。不支持直接替换原 PDF 中现有段落、OCR、交互式表单编辑或数字签名。搜索定位到匹配页面，扫描图像需要先有文字层。

密码文件需输入正确密码，尊重读取到的修改权限。应用用私有解密工作副本阅读，另存副本不加密。异常或超大文件会显示错误提示，不能保证所有损坏文件或特殊 PDF 扩展均能兼容。

## 构建

使用 JDK 17、Gradle 8.11.1、Android Gradle Plugin 8.9.2、Android SDK 35 / Build Tools 35.0.0；PDFBox-Android 2.0.27.0。

克隆仓库后，先安装 JDK 17 和 Android SDK，设置 `JAVA_HOME`、`ANDROID_HOME`，并安装 SDK Platform 35 / Build Tools 35.0.0。Windows 可用 Gradle Wrapper 构建供开发使用的 debug APK：

```powershell
.\gradlew.bat assembleDebug
```

输出位于 `app/build/outputs/apk/debug/app-debug.apk`。debug 使用开发签名，不能直接覆盖已安装的正式版。

正式版构建命令为 `./gradlew.bat assembleRelease`，需要先配置自己的签名密钥与参数。项目发布密钥保存在本机 `.signing/paperpdf.jks`，参数从被忽略的 `.signing/release.properties` 或 `PAPERPDF_KEYSTORE_PASSWORD`、`PAPERPDF_KEY_ALIAS`、`PAPERPDF_KEY_PASSWORD` 环境变量读取。原发布密钥不随仓库提供；只有沿用原密钥的正式版才能覆盖升级现有纸阅。

`build.ps1` 是本地工具链辅助脚本，需要 `.tools` 中的 JDK 17、Gradle 8.11.1 和 Android SDK；这些工具不随 Git 仓库上传。配置好工具和签名后，PowerShell 执行 `./build.ps1` 构建并验证正式版，`./build.ps1 -Test` 还会运行集成测试。集成测试会清理测试应用的数据，只在专用模拟器或可清理的测试设备上运行。版本号在 `gradle.properties` 维护，变更记录见 [CHANGELOG](CHANGELOG.md)。

集成测试位于 `app/src/androidTest`，在安卓系统内验证 PDF 与 Word 阅读、编辑、导出和草稿恢复。[1.6.1 发布记录](docs/release-1.6.1.md)包含 34 项模拟器集成测试摘要；[1.6.0 发布记录](docs/release-1.6.0.md)保留 Word 功能验证摘要。

实体手机验证时，请检查打开文档、双指缩放、翻页/连续滚动、显隐工具栏、横竖屏、批注、草稿恢复和保存。模拟器通过不能替代品牌手机、旋转传感器、微信/QQ 和具体文件提供方实测。本地验收包与详细日志不随源码仓库上传。

公开仓库的数据范围与隐私说明见 [仓库隐私说明](docs/repository-privacy.md)。

## 依赖与技术资料

- [Android 文件访问文档](https://developer.android.com/training/data-storage/shared/documents-files)
- [PDFBox-Android 官方仓库](https://github.com/TomRoush/PdfBox-Android)
- [Gradle 8.11.1](https://docs.gradle.org/8.11.1/release-notes.html)

Word 旧 DOC 解析使用 Apache POI 5.4.1；DOCX 使用受限 ZIP/XML 解析与 Android 本地分页。为保持 minSdk 23，构建时用不启用内存映射清理的 Android 适配器替换 POI 的桌面 CleanerUtil；没有降低 POI 版本。依赖许可和适配说明随 APK 放在 `assets/licenses`。
