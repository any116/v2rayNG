# 依赖注入规范（Hilt）

## 1. 唯一的 DI 框架

生产代码使用 Hilt。`BaseViewModelFactory` 与 `baseViewModels {}` 已从生产路径移除，
不得新增自定义 `ViewModelProvider.Factory`，不得手工 `new` ViewModel。

## 2. @Inject 构造函数禁止全参数默认值

**Kotlin 在一个构造函数的全部参数都带默认值时会合成一个无参构造函数重载，并把
`@Inject` 复制到该重载上。** Dagger 随后报
`may only contain one injected constructor`，并连带产生 `[Dagger/MissingBinding]`。

因此，`@Inject constructor` 的参数一律不写默认值：

```kotlin
// 错：唯一参数带默认值 → 合成出 @Inject XxxRepository()
open class XxxRepository @Inject constructor(
    @IoDispatcher io: CoroutineDispatcher = Dispatchers.IO,
) : BaseRepository(io)

// 对
open class XxxRepository @Inject constructor(
    @IoDispatcher io: CoroutineDispatcher,
) : BaseRepository(io)
```

`BaseRepository` 自身的 `io` 默认值可以保留：它是抽象类，永不被注入。

单元测试显式传 dispatcher（`Dispatchers.Unconfined` 或 `StandardTestDispatcher`），
这比依赖默认值更可控，见 `repository-rules.md` 第 4 节。

## 3. 模块划分

`di/` 下只放**接缝**，不放业务：

| 模块 | 提供 |
| --- | --- |
| `Qualifiers.kt` | `@IoDispatcher` / `@DefaultDispatcher` |
| `DispatcherModule` | 两个 dispatcher，unscoped |
| `NetworkModule` | 复用 `HttpUtil.sharedClient` |
| `ThemeModule` | 复用 `ThemeRepository` object，`@Provides` 而非 `@Binds` |

**不要把现有 object 包一层新的 `@Singleton` 类再宣称完成了依赖隔离。**
既有 object 就是那个实例，模块只负责把它 re-export 进图里。

## 4. object 接缝的桥接

`handler/` 下的 object（`MmkvManager`、`SettingsManager`、`AngConfigManager` …）
保持 object，Repository 直接调用，**不注入**。它们必须跨进程可用，而 Service /
Worker 进程里没有 UI 侧的图。

只有当一个 object 同时被 Hilt 内外的调用方共享、且需要在测试里替换时，才抽接口
桥接（当前唯一一例：`ThemeStore` ← `ThemeRepository`）。抽接口需要评审，理由写进 KDoc。

## 5. Application

`AngApplication` 带 `@HiltAndroidApp`。三条铁律：

1. `attachBaseContext` 里**不得**访问任何注入字段 —— 那时还没注入。
2. 注入在 `super.onCreate()` 内部完成，所以依赖注入字段的对象必须在
   `super.onCreate()` **之后**构建，不能写成属性初始化器。
3. 每个进程都会创建自己的 Application 和自己的 Hilt 图。任何 singleton 都不得在
   注入阶段读 MMKV，因为 `MmkvManager.initialize()` 排在 `super.onCreate()` 之后。

## 6. 平台组件

Activity / Service / Receiver 用 `@AndroidEntryPoint`。系统创建的非标准对象用
受限的 `EntryPoint`，且只允许出现在系统入口适配层，**不得**退化成普通
Repository 的 Service Locator。Widget 不注入 Activity ViewModel；注入对象不跨进程传递。
