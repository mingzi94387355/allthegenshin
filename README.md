# All the Genshin（原神，启动！）

一个 **纯客户端** 的 Minecraft Forge 1.20.1 模组（`mods.toml` 里 `clientSideOnly=true`，专用服务端直接忽略它）：

> 在启动阶段去注册表里找原神；游戏启动完成后检查你有没有装某些"不该装"的模组；
> 一旦发现，**游戏里直接报错 "Never gonna give you up..."（但游戏不关闭）**，然后原神，启动！

参考了 allcrash-forge 的玩法（配置文件里配一个 `crash_mod` 列表）。

## 完整流程

```
游戏启动阶段 (FMLCommonSetupEvent)
    └─ 是 Windows？ → reg query 查注册表，找出原神安装路径并缓存
                     （HKCU\Software\miHoYo\HYP\1_1\hk4e_cn → GameInstallPath 等）
    └─ 不是 Windows？ → 跳过

游戏启动完成 (客户端进入主菜单)
    └─ for 循环遍历配置文件里的 crash_mod 列表
         for (String id : crash_mod) {
             if (ModList.get().isLoaded(id)) hits.add(id);
         }
    └─ 一个都没命中 → 什么也不做
    └─ 命中 → 抛出报错 "Never gonna give you up..."：
          ├─ 崩溃报告照样写进 crash-reports/ 并打进日志
          ├─ 报错界面弹在屏幕上，**游戏继续运行**（不会关掉）
          ├─ 用浏览器打开 https://www.bilibili.com/video/BV1GJ411x7h7/ 放首歌
          └─ 然后是"该打开什么"：
                ├─ 不是 Windows → 用浏览器打开 云原神
                │     https://ys.mihoyo.com/cloud/?utm_source=default#/
                └─ 是 Windows
                      ├─ 什么都没找到 → 用浏览器打开 原神下载页
                      │     https://ys-api.mihoyo.com/event/download_porter/link/ys_cn/official/pc_backup322
                      ├─ 只找到米哈游启动器 → 带原神参数启动启动器
                      │     launcher.exe --game=hk4e_cn
                      └─ 找到游戏本体 → 启动原神（YuanShen.exe / GenshinImpact.exe）
```

### 为什么不用 `Minecraft.crash()` / 直接抛异常

在 1.20.1 里这两条路最后都会把游戏关掉：

* `Minecraft.crash(report)` 写完报告就 `ServerLifecycleHooks.handleExit(-1)`；
* 在 tick 里裸抛异常也一样 —— `Minecraft.run()` 会把它接住，然后照样走 `crash()` 退出
  （所以 allcrash 那种"直接抛"的表现就是游戏秒退，原神还没来得及起来窗口就没了）。

想要"报错但游戏不关"，只能自己写报告（`CrashReport.saveToFile`）+ 自己显示报错界面，
见 `ClientCrashPresenter` 和 `GenshinCrashScreen`。

### 启动原神

**游戏本体要管理员权限**：`YuanShen.exe` 带 `requireAdministrator` 清单，普通权限直接
`CreateProcess` 会失败：

```
CreateProcess error=740, 请求的操作需要提升。
```

所以启动顺序是：直接启动 → 失败就改用 `cmd /c start`（走 ShellExecuteEx，会弹 **UAC 提权窗口**，
点"是"就能进游戏）→ 再不行就退而求其次开启动器（同样带原神参数）。
如果 Minecraft 本身就是用管理员身份运行的，第一步就成功了，不会有 UAC 弹窗。

**只有启动器时的参数**从哪来：读桌面 / 开始菜单里的“原神”快捷方式
（本机 `C:\Users\lenovo\Desktop\原神.lnk` 的目标是 `…\miHoYo Launcher\launcher.exe`、
参数是 `--game=hk4e_cn`），用 PowerShell 的 `WScript.Shell` 读，结果写临时文件再读回来；
读不到就退回注册表里 `HYP\1_1\<游戏>` 的游戏名，最后才是默认的 `--game=hk4e_cn`。

（`prefer_launcher = true` 可以直接优先走启动器。）

## 配置文件

位置：`config/allthegenshin-common.toml`（首次启动自动生成）

```toml
[allthegenshin]
	enable = true              # 总开关
	react_enable = true        # 报错后是否启动原神 / 打开浏览器
	crash_mod = ["examplemod"] # 要检测的模组 id 列表（modId，不是文件名）
	genshin_path = ""          # 手动指定原神路径，留空 = 注册表自动查找
	prefer_launcher = false    # true = 优先启动 launcher.exe
```

想让"某个模组"触发报错，就把它 `mods.toml` 里的 `modId` 填进 `crash_mod`，例如：

```toml
crash_mod = ["examplemod", "jei", "allcrash"]
```

## 怎么找到原神（Windows）

按优先级分三层扫描注册表，任一层先找到就直接收工（常见情况 1 秒内完成）：

1. **米哈游启动器**：把整个 `HKCU\Software\miHoYo\HYP` 子树一次 `reg query /s` 查完
   （里面有启动器的 `InstallPath`，也有 `HYP\1_1\hk4e_cn` 的 `GameInstallPath`）。
   这个键里全是小字段，所以一次查询就够，正常机器上几十毫秒。
2. **卸载信息 / 机器级键**：`...\CurrentVersion\Uninstall\原神`、`HKLM\SOFTWARE\miHoYo` 等
3. **按值名探测**：`HKCU\Software\miHoYo\原神`、`...\Genshin Impact` 这类键。
   这些键里塞了几百 KB 的十六进制配置大字段，直接 `reg query /s` 会卡十几秒，
   所以只对它按 `GameInstallPath` / `InstallPath` / `InstallLocation` / `GamePath` /
   `UninstallString` / `DisplayIcon` 这些名字单独取值。

每次查询都有超时（管道 1 秒 / 临时文件 3 秒）和 512 KB 输出上限，整个扫描还有 8 秒总预算；
如果某个环境下子进程的输出管道读不出来（沙箱、安全软件等），会自动改用
`cmd /c "reg query ... > 临时文件"` 的方式重试，并且后面都走这条路。
注册表全都没找到时，会退回扫描常见安装目录（`Program Files`、各盘根目录、
`<某盘>:\Program Files\miHoYo Launcher\games\Genshin Impact Game` 等）。

找到目录后优先取游戏本体 `YuanShen.exe`（国服）/ `GenshinImpact.exe`（国际服），
同一台机器上同时存在 `launcher.exe` 时不会被误当成游戏。

## 构建

```bash
./gradlew build            # 需要联网时去掉 --offline
# 产物：build/libs/allthegenshin-1.0.0-forge1.20.1.jar
```

离线（依赖已缓存）构建：

```bash
./gradlew build --offline
```

开发环境直接跑客户端（会真的弹报错界面并且真的启动原神，注意）：

```bash
./gradlew runClient
```

## 代码结构

| 文件 | 作用 |
| --- | --- |
| `allthegenshin.java` | 主类：注册配置、启动阶段查注册表（纯客户端，服务端不加载） |
| `ATGConfig.java` | 配置文件（`crash_mod` 列表等） |
| `GenshinLocator.java` | 注册表 / 常见目录查找原神，路径解析，读“原神”快捷方式的启动参数 |
| `GenshinTakeover.java` | for 循环检测 `crash_mod` → 抛出报错 → 启动原神/浏览器 |
| `GenshinActions.java` | 用浏览器打开网址、启动原神本体 / 启动器（含 UAC 提权兜底） |
| `ClientStartupWatcher.java` | 客户端"启动完成"判定（进主菜单后触发检测） |
| `ClientCrashPresenter.java` | 写 crash-reports、打日志、显示报错界面（**不关游戏**） |
| `GenshinCrashScreen.java` | "Never gonna give you up..." 报错界面本体 |

## 免责声明

这是一个整活模组：它会**故意报错**并**启动原神**。
请只在自己的整合包里用，别拿去做坏事（比如伪装成"模组冲突"骗别人）。
