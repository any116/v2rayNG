# Android View 收尾迁移计划

状态：待实施。

## 1. 目标

在保留现有页面架构与平台行为的前提下：
1. About 开源许可弹窗改为本地 JSON 驱动的 Compose UI。
2. 桌面组件改用 Compose 风格声明式实现。
3. 区分 Android 16/API 36+ 与 API 24—35 的能力与验收。
4. 清理不再使用的 View 实现和资源。

不重写已经完成 Compose 化的主体页面。

## 2. 已确认现状

### About

- AboutActivity 继承 BaseActivity。
- AboutScreen 使用 BaseScreen。
- AboutEvent.ShowOssLicense 触发展示。
- AboutDialogHost 在 UI 持有弹窗状态。
- OssLicenseDialog 是 Compose AlertDialog。
- 内部 LicenseWebView 使用 AndroidView。
- WebView 加载 open_source_licenses.html。
- WebView 已关闭 JavaScript，并在 onRelease 释放。
- 翻译贡献名单使用独立 translators.json。

### 桌面组件

- receiver/WidgetProvider.kt 继承 AppWidgetProvider。
- 使用 R.layout.widget_switch。
- 点击通过 AppConfig 的 widget action 进入。
- 启停委托 LauncherManager。
- 状态依据 CoreServiceManager 和服务广播。
- WidgetProvider 位于服务相关进程。
- AngApplication 手动初始化带多进程配置的 WorkManager。

## 3. 总体阶段

| 阶段 | 交付 |
|---|---|
| V0 | 全源码与资源使用清单 |
| V1 | 许可 JSON 生成与校验 |
| V2 | Compose 许可弹窗 |
| V3 | Glance 基础桌面组件 |
| V4 | Android 16+ 增强与兼容验证 |
| V5 | 删除无引用旧实现并验收 |

V0 必须重新搜索：
AndroidView、WebView、setContentView、LayoutInflater、RemoteViews、layout 资源引用及限定符目录。

目录列表不能替代完整引用扫描。

## 4. About 许可 JSON 方案

### 4.1 数据来源

现有工程已应用许可证插件。

实施时确认该插件的：
- 实际任务名。
- JSON 报告能力。
- 输出目录。
- 依赖配置范围。
- 是否包含传递依赖和本地依赖。
- 许可证正文获取方式。

优先使用结构化报告，不在运行时解析 HTML。

插件不能提供完整字段时，增加构建期转换任务和经过审查的补充清单。

### 4.2 目标文件

新增目标：
- dto/license/ 下的 JSON DTO 与纯解析器。
- repository/ 下的许可数据读取能力。
- ui/about/components/ 下的许可列表与详情组件。
- 构建生成的 open_source_licenses.json。
- 必要的本地许可证正文资源。

这些路径是目标，不代表当前已存在。

### 4.3 JSON 契约

```json
{
  "schemaVersion": 1,
  "libraries": [
    {
      "id": "group:artifact:version",
      "name": "Library display name",
      "version": "version",
      "projectUrl": "https://example.org",
      "copyrightNotices": [],
      "licenses": [
        {
          "id": "SPDX-or-project-id",
          "name": "License name",
          "url": "https://example.org/license",
          "textAsset": "licenses/license-id.txt"
        }
      ]
    }
  ]
}
```

约束：
- id 稳定且唯一。
- 多许可证保持列表，不压扁为一个字符串。
- 保留版权声明和 NOTICE 要求。
- 许可证全文必须离线可访问。
- textAsset 只允许指向打包内受控相对路径。
- 未知许可证不能静默省略。
- 输出排序确定，避免无意义 diff。
- translators.json 保持独立。

### 4.4 构建集成

- 生成任务声明 inputs/outputs。
- 使用 generated assets source directory 接入资源合并。
- 任务依赖由 Gradle 配置，不依赖开发者手动复制。
- 验证干净构建也能产生 JSON。
- 网络获取不得发生在应用运行时。
- 缺失许可信息提供明确构建报告。
- 对无法自动生成的条目维护带来源说明的补充文件。

完整性对比以实际发布依赖清单为依据，而不是只比较旧 HTML 的条目数量。

### 4.5 运行时读取

Repository 后台读取 assets。
纯 parser 返回 DTO 或明确解析错误。

ViewModel/状态层提供：
- 未加载。
- 加载中。
- 列表。
- 失败和重试。
- 当前许可证详情 ID。

不使用 runIO(emptyList()) 把资源损坏呈现为“没有开源库”。

## 5. About Compose UI

### 5.1 展示

保留“关于 → 开源许可”的入口。

列表显示：
- 名称。
- 版本。
- 许可证名称。
- 项目链接入口。

点击条目进入详情或嵌套受控内容区：
- 许可证正文可滚动、可选择复制。
- 支持长文本和大字体。
- 外链经 AppRoute.OpenUrl 处理。
- 关闭和返回行为一致。

### 5.2 状态

允许继续使用 UI DialogHost。

建议以可保存的弹窗标识与详情 ID 恢复展示状态。
正文和完整库列表由 Repository 重新读取，不写入 SavedStateHandle。

不得同时保留 HTML 弹窗与 JSON 弹窗的双重事件消费。

### 5.3 清理

验收后移除：
- LicenseWebView。
- AndroidView/WebView 相关 import。
- LICENSE_ASSET_URL。
- 无引用 HTML 打包输出。
- 仅服务该 WebView 的兼容代码。

许可证生成插件是否移除，取决于是否继续承担 JSON 生成，不因删除 WebView 自动删除插件。

## 6. 桌面组件技术方案

### 6.1 基础方案

采用 Glance 的 AppWidget APIs，提供所有支持设备可用的基础开关组件。

普通 Compose 的 MaterialTheme、Modifier、Button 不直接用于 Glance。
共享的是业务状态、语义色定义和格式化逻辑，不是整个 AppTheme。

### 6.2 目标职责

- WidgetProvider：保留组件身份，迁为 Glance 接收器或稳定转发入口。
- Widget UI：无状态声明式渲染。
- WidgetStateRepository：读取真实状态快照。
- WidgetActionHandler：启动、停止、权限转交和去重。
- WidgetUpdateCoordinator：合并更新并刷新所有实例。

命名在实施时与现有包结构统一。

### 6.3 Android 版本矩阵

| 设备 | 基础实现 | 增强策略 |
|---|---|---|
| API 24—30 | Glance 生成兼容 RemoteViews | 静态状态、基础点击、传统尺寸 |
| API 31—35 | Glance + 对应系统尺寸/主题能力 | 使用支持的圆角、响应式尺寸和预览 |
| API 36+ | 基础实现始终可用 | 经过验证后启用 Remote Compose 增强 |

Android 16 是本计划增强路径的验收分界，不代表 Glance 从 Android 16 才可用。

RemoteViews.DrawInstructions 构造能力在官方 API 中早于 API 36。
因此不能把“存在 DrawInstructions”与“完整增强组件可用”视为同一条件。

### 6.4 Remote Compose 接入门槛

接入前验证：
1. 选定库版本及实验性状态。
2. 与当前 Compose、Kotlin、最低 API 的兼容。
3. 文档编码版本与系统支持版本。
4. 真实启动器渲染、点击和无障碍。
5. 更新失败和不支持宿主的回退。
6. 高低版本载荷不能违反 RemoteViews 混用限制。

增强仅限视觉反馈和尺寸过渡，不改变启停正确性。

不依赖未经验证的“Glance 自动提供完整回退”假设。
回退必须在本项目测试中实际成立。

## 7. Widget 状态与操作

### 7.1 状态模型

建议：
- Stopped。
- Starting。
- Running。
- Stopping。
- PermissionRequired。
- Failed。
- Unknown。

真实状态由服务侧确认。

点击只改变“请求中”，不能立刻伪装成已连接。
服务没有响应时通过超时和重新查询恢复。

### 7.2 权限

首次 VPN 授权必须打开可见 Activity 走系统流程。

桌面接收器不直接弹权限界面。
后台启动限制不满足时提供打开应用路径。

### 7.3 去重

连续点击使用幂等命令和请求中保护。

PendingIntent 或 Action 参数必须带明确动作身份。
存在每实例配置时加入 appWidgetId，避免不同实例参数覆盖。

### 7.4 更新

触发：
- 添加组件。
- 尺寸变化。
- 用户点击。
- 服务启动/停止/失败。
- 主题和语言变化。
- 应用更新与状态恢复。

不通过每秒 WorkManager 或轮询维持状态。

更新请求合并，但最终状态不得被丢弃。
删除组件后清理其专属状态。

## 8. 多进程与 WorkManager

Glance 更新不能假设执行在 WidgetProvider 所在进程。

CoreServiceManager 的进程内状态不能直接成为跨进程事实源。

必须验证：
- Glance 任务实际执行位置。
- WorkManager 当前默认后台进程配置。
- UI 进程、服务进程、后台进程之间的状态读取。
- 进程被杀后的恢复。
- Hilt 接入后的依赖创建。

必要时通过现有消息协议请求服务快照。
不把“读取某进程 object 的 false”直接解释为服务已停止。

若 Hilt 未完成，Widget 接口保持可注入兼容边界，不自行创建另一套 DI。

## 9. 保留用户已有组件

优先保持 WidgetProvider 的完整组件名和元数据身份。

直接更换 Provider 名可能使已有桌面组件失效。
确需更名时先验证升级迁移能力，不能要求用户无提示地重新添加。

保留系统需要的 provider XML、预览和初始加载资源。
迁移完成不以“XML 数量归零”为验收条件。

## 10. 测试

### About
- JSON schema 和排序。
- 多许可证。
- 缺字段和损坏 JSON。
- 正文路径越界。
- 离线可用。
- 大字体、RTL、深色。
- 返回、关闭和重建。
- 依赖清单完整性。

### Widget
- API 24、31、35、36 和当前目标 API。
- 至少系统启动器与一种常见第三方启动器。
- 新增、缩放、删除、升级。
- 多实例。
- 服务进程和 UI 进程分别被杀。
- 启停失败、权限未授予、连续点击。
- 主题、语言、减少动画。
- 增强载荷失败后的基础回退。

## 11. 回滚

About：
- 在删除旧资源前完成 JSON 正文与法律完整性验收。
- 运行时资源损坏显示明确错误，不悄悄隐藏许可入口。

Widget：
- 保持 Provider 身份。
- 增强路径可关闭并退回已验证的基础 Glance 实现。
- 回滚不能要求清除用户配置或重装。

## 12. 完成条件

- 许可弹窗不依赖 WebView。
- 许可信息完整、离线可读。
- Widget 基础路径覆盖所有支持版本。
- API 36+ 增强和低版本路径分别通过验收。
- 已有桌面组件升级后继续可用。
- 无重复权限请求或启停命令。
- 旧实现和资源只在无引用后删除。
