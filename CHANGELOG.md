# Changelog

本文件遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/) 格式。

## [v0.3.3] — 移除 Termux 兼容

### 移除
- **Termux 中文兼容**（gboard-termux-ime 移植）：进出终端自动切换语言、
  终端内 EditorInfo 归一化。设置项 `termux_enabled` 一并移除，
  升级后旧配置中的该键自动忽略。
- compat-test 锚点 `latin_ime` 随之移除（仅服务于 Termux hook）。

### 文档
- README 功能表 / 架构图 / 参考文献同步更新（模块计数 12 → 11）；
  `xposed_desc` 修正为新项目口号。

## [v0.3.2] — 配置持久化兜底

### 修复
- **配置不跨设备重启持久（上游 Vector 2.2 缺陷）**：remote preferences 为内存态，
  daemon 重启（设备重启）丢失全部键，所有开关回默认。探针实验确认无落盘文件。

### 新增
- **本地配置镜像兜底**（App 与 Hook 两侧独立副本）：
  - App 每次 `write` 打 `pref_sync_time` 同步戳并镜像到 App 私有文件；
  - Hook 侧把最近读到的配置镜像到 Gboard 私有目录
    （`files/switchboard_config.cache`）；
  - 两侧均以 `pref_sync_time` 判定 daemon 是否持有数据——丢失时自动回退
    本地镜像，重启后开关/数值原样恢复，用户无感知；用户改任意开关即重新
    覆盖镜像。UI 显示与导出/导入同步使用镜像回退。

## [v0.3.1] — 真机测试修复轮

### 修复
- **剪贴板敏感判定误杀（严重）**：insert 扫描用 `getAsString` 遍历所有列，
  13 位毫秒时间戳列命中"12-19 位纯数字 = 卡号"规则，导致**所有**剪贴板记录
  （含正常文本）被拦截、历史永远为空。现在只检查 String 类型的值。
  （模拟器真机测试发现；已补回归测试）
- **`proceedWith` 单参误用（4 处）**：libxposed API 无 vararg 重载，
  原写法把整个参数数组当作单个参数传入，query 调用报
  `Expected receiver ... but got Object[]`。改为 `proceedWith(thisObject, args)`。
  涉及 ClipboardHook.query、PunctuationHook ×2、InputTypeHook。

## [v0.3.0] — 更名 Switchboard

### 变更
- 项目更名：**MGGA → Switchboard**（「把 Gboard 的每一个开关，都交到你手里」）
  - applicationId / namespace：`com.mgga.gboard` → `com.switchboard.gboard`
  - remote preferences group：`mgga` → `switchboard`（升级后开关需重新调整；
    可先在旧版导出配置 JSON，更新后粘贴导入）
  - 日志 TAG：`MGGA` → `Switchboard`（排查命令 `logcat -s Switchboard`）
  - 保险库数据库 / 覆盖层 tag / 文件路径等内部标识同步迁移

### 修复
- **工具栏注入必然失败**（模拟器真机测试发现）：`ToolbarHook.injectToolbar` 在
  `container.addView(inputView)` 之前未先执行 `frame.removeView(inputView)`，
  抛 `IllegalStateException: The specified child already has a parent`，
  任何 Gboard 版本上工具栏都无法出现（此前被 FeatureHub 的 try/catch 隔离掩盖）。
  修复后注入成功，工具栏 UI 正常可见。

## [v0.2.0] — 现代 UI 与工程化

### 新增
- 设置 App 全量重写为 **Jetpack Compose + Material 3**（动态取色、底部双 Tab：功能开关 / 诊断）
- **诊断页**：LSPosed 服务状态、Gboard 作用域、Gboard 版本、开关概览、logcat 排查指引
- **功能搜索**：70+ 开关实时过滤（标题/描述/键名）
- **配置导出/导入**：JSON 进剪贴板，粘贴即恢复
- **i18n**：全部界面文案资源化（简体中文默认 + English）
- **Flag 预设表外置** `assets/flag_presets.json`（16 组 190+ flag），社区可以数据 PR 更新

### 内部
- libxposed API 102 骨架不变；hook 侧零改动


## [v0.1.0] — 首个公开版本

### 功能（全部独立开关）

- **剪贴板增强**：历史条数 / 保存天数解除限制；敏感信息（OTP / 卡号 / 关键词 / 密码框）不入历史
- **Flag 覆写引擎**：DexKit 动态定位 Phenotype flag 读取器；17 组预设（AI 写作 / Rambler /
  多语言 / Emoji Kitchen / 禁遥测 / 圆角键帽 / 词学习…）+ 自定义 flag JSON
- **AMOLED 纯黑**：深色模式 surface 容器改纯黑
- **候选 emoji 提亮**：候选栏 emoji 100% 不透明
- **密码框完整键盘**：受限输入框显示全键盘（默认关）
- **九键半角标点**：数字页 `，。` → 半角、标点栏 → `+ − × ÷`（输出 + 键面同步）
- **键盘工具栏**：剪贴板保险库（文本+图片）/ 粘贴 / 全选 / 选择模式 / 光标 / 撤销（含摇一摇）/
  文本快捷 / 截图自动入库 / Auto Expand 接管
- **候选词译词**：12 组离线词典（zh↔en/ja/de/fr/es/ru）、构词回退、DP 分词组合、
  用户词表导入、注入横幅 —— 纯离线
- **双拼键帽提示**：6 方案、动态提示、主字母隐藏/字号、分号键、灵活 emoji 联想
- **Termux 兼容**：拼音正常输入 + 进出终端自动切换语言
- **RGB 灯效**：9 特效 / 键帽流光 / 滑行轨迹 / WPM 过热 / 底光 / root 恢复原厂主题

### 工程设施

- `compat-test/`：离线锚点扫描器（字符串池 + class_data 方法/字段级解析），
  已验证 Gboard 16.6.3 与 17.8.3-beta
- 兼容性哨兵 workflow：每周自动拉取新版 Gboard、刷新矩阵、锚点漂移自动开 issue
- JVM 单元测试：词典管线 / 语法修正 / 九键映射 / 敏感文本规则
- GitHub Actions 构建与 tag 自动发版；`update.json` 供 LSPosed 管理器更新检查

### 设计决策

- 100% 本地：不申请 INTERNET 权限，无任何云端引擎依赖；
  语音换源与在线翻译类功能刻意不整合
- libxposed 新 API 102（remote preferences 配置通道，`requestScope` 一键作用域）
