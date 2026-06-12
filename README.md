# Code Translation Plugin

IDEA 插件：代码平移助手，用于将只读源目录的代码手动合并到当前项目。

## 功能

- **文件对比**：扫描源目录（如 `onm-source-package`）和目标目录（如 `minmetals-hcm-pm-biz`），列出差异文件
- **Diff 视图**：双击打开 IntelliJ 内置 Diff，左侧只读源 ➔ 右侧可编辑目标
- **手动合并**：逐块接受/拒绝差异，直接在右侧编辑
- **一键拷贝**：「仅源」状态的文件一键拷贝到目标目录
- **状态过滤**：按"仅源 / 有差异 / 一致"过滤文件列表

## 使用步骤

### 1. 构建 & 安装
～·
```bash
cd code-translation-plugin
./gradlew buildPlugin
```

生成的插件 zip 在 `build/distributions/`，在 IDEA 中 **Settings → Plugins → ⚙ → Install Plugin from Disk** 安装。

或者直接运行：

```bash
./gradlew runIde
```

会打开一个加载了插件的 IDEA 沙箱实例。

### 2. 配置

**Settings → Tools → Code Translation**：

| 配置项 | 说明 | 示例 |
|--------|------|------|
| 源目录（只读） | 被平移的原始代码目录 | `/path/to/onm-source-package` |
| 目标目录（可写） | 当前项目代码目录 | `/path/to/minmetals-hcm-pm-biz` |
| 文件后缀 | 要对比的文件类型 | `.java,.xml,.properties,.yaml` |
| 最大文件数 | 限制扫描量 | `500` |
| 忽略目录 | 跳过的目录 | `.git,.idea,target` |

### 3. 使用，可直接下载插件安装

1. 右侧打开 **Code Translation** 工具窗口
2. 点击 **刷新** 扫描文件
3. 按状态过滤：全部 / 仅源 / 有差异 / 一致
4. 双击行 → 打开 Diff 视图手动合并
5. 或点击操作列 📋 直接拷贝源文件到目标

## 项目结构

```
src/main/kotlin/cn/minmetals/hcm/pm/plugin/codetrans/
├── CodeTranslationToolWindowFactory.kt   # ToolWindow 注册
├── TranslationSettings.kt                # 持久化配置
├── TranslationConfigurable.kt            # Settings UI
├── TranslationToolWindowPanel.kt         # 主面板（文件列表 + 操作）
├── FileScanService.kt                    # 文件扫描 & 对比
├── DiffViewer.kt                          # Diff 视图（核心）
└── action/
    ├── CopyToTargetAction.kt             # 拷贝到目标
    ├── OpenDiffAction.kt                 # 打开Diff
    └── RefreshAction.kt                  # 刷新列表
```
