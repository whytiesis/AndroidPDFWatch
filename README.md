# 纸阅 PDF

原生 Android 本地 PDF 阅读与批注应用，包名 `cn.paperpdf.reader`。

## 下载

[⬇️ 下载最新版 APK（v1.4.0）](https://github.com/whytiesis/AndroidPDFWatch/releases/download/v1.4.0/PaperPDF-1.4.0.apk)

[查看全部历史版本](https://github.com/whytiesis/AndroidPDFWatch/releases)

## 安装与使用

支持 Android 6.0（API 23）及以上。仓库只保留源码，不提交 APK、发布签名或本地构建产物；可按下文从源码构建并安装。

1. 打开纸阅，点击“打开手机中的 PDF”。应用会列出设置中已授权目录的全部 PDF，可搜索文件名并直接打开；点击“从文件中选择”可从系统文件选择器选取其他 PDF。也可在文件管理器中使用“打开方式”或“分享”选择纸阅。
2. 微信或 QQ 已下载的 PDF：进入首页右上角“设置”，点击“添加微信 / QQ 文件夹”授权一次，以后可在“打开手机中的 PDF”中直接浏览、搜索这些 PDF；也可以在微信或 QQ 中点击文件的“用其他应用打开”或“分享”，选择纸阅 PDF。
3. 默认单页阅读支持双指缩放、拖动、双击放大；左右滑动或底部按钮翻页，点击页码跳转。开启“连续滚动阅读”后也支持双指缩放（1–5 倍）、双击放大/还原和拖动，默认收起左右白边使正文适宽，可在设置关闭。适宽只改变显示，不修改文件、不重排 PDF 段落；无法可靠判断白边时显示完整页宽。
4. 阅读模式轻触 PDF 隐藏/显示工具栏和系统栏；隐藏时按返回先呼出工具栏。双击仍缩放；编辑时的点击不会隐藏 UI。横竖屏跟随系统自动旋转设置，横屏单页适合页宽，长页上下拖动阅读；工具栏在上下方，侧边导航栏和挖孔区域保留安全边距。画笔可手写圈画，荧光工具拖出矩形标记，文字工具点击页面后输入中文或多行批注。
5. 主页右上角设置管理文件目录、微信 / QQ 文件夹、连续滚动和夜间阅读；阅读页右上角菜单提供同样的阅读切换，以及搜索、页面旋转/删除/排序、重做和分享。底部工具栏横向滑动可找到撤销。
6. 点击保存，选择“覆盖原文件”或“另存为 PDF”。覆盖保存需要原文件写入权限；旧目录只有读取权限时，按提示重新选择同一原文件授权。写入前备份并校验结果，失败时尝试恢复。只读微信/QQ 临时分享文件请先保存到可写本地位置再打开。
7. 再次打开同一文件，自动恢复该文件的上次页码；连续模式同时恢复页内位置。文件移动、更换提供方或临时分享 URI 变化后可能被视为新文件。夜间阅读同步上下工具栏、状态栏、导航栏和弹窗颜色。

应用不申请网络或全部文件访问权限；使用 Android Storage Access Framework 的文件授权。最近文件、阅读位置和一个当前编辑草稿保留在本机。打开新文档前请另存需要保留的修改。

Android 11 及以上不能授权其他应用的 `Android/data` 私有目录，也不能将整个 Download 根目录作为长期授权文件夹。因此这些位置请使用“从文件中选择”（会优先打开 Download），或在微信 / QQ 内分享/打开文件；应用不会绕过系统访问限制。已授权的其他文件夹会记住，之后可以直接浏览。

## 修改能力的范围

支持添加文字、手写及矩形荧光标记，旋转、删除、重排页面和撤销/重做。导出时保留原页面内容，将新增批注写入页面内容；批注导出后无法作为独立批注对象在其他软件中再次编辑。不支持直接替换原 PDF 中现有段落、OCR、交互式表单编辑或数字签名。搜索定位到匹配页面，扫描图像需要先有文字层。

密码文件需输入正确密码，尊重读取到的修改权限。应用用私有解密工作副本阅读，另存副本不加密。异常或超大文件会显示错误提示，不能保证所有损坏文件或特殊 PDF 扩展均能兼容。

## 构建

使用 JDK 17、Gradle 8.11.1、Android Gradle Plugin 8.9.2、Android SDK 35 / Build Tools 35.0.0；PDFBox-Android 2.0.27.0。

配置 JDK 17 和 Android SDK 35 后，可执行 `./gradlew assembleDebug` 构建调试包，或在已连接测试设备时执行 `./gradlew connectedDebugAndroidTest`。版本号在 `gradle.properties` 统一维护，变更记录见 `CHANGELOG.md`。

发布签名需将密钥放在 `.signing/paperpdf.jks`，并设置 `PAPERPDF_KEYSTORE_PASSWORD`、`PAPERPDF_KEY_ALIAS` 和 `PAPERPDF_KEY_PASSWORD` 环境变量。签名密钥和密码不进入版本控制。

集成测试源码位于 `app/src/androidTest`，测试输出、日志、截图和本地验收记录不提交到仓库。

## 依赖与技术资料

- [Android 文件访问文档](https://developer.android.com/training/data-storage/shared/documents-files)
- [PDFBox-Android 官方仓库](https://github.com/TomRoush/PdfBox-Android)
- [Gradle 8.11.1](https://docs.gradle.org/8.11.1/release-notes.html)

第三方许可随 APK 放在 `assets/licenses`。
