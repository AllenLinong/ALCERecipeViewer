# ALCERecipeViewer 构建教程

## 📋 构建环境要求

### 系统要求
- **操作系统**: Windows 10/11, macOS 10.15+, Linux (Ubuntu 18.04+)
- **Java**: JDK 17 或更高版本
- **构建工具**: Apache Maven 3.6.0+

### 软件安装

#### 1. 安装 Java Development Kit (JDK 17+)

**Windows:**
```bash
# 下载并安装 OpenJDK 21
# 下载地址: https://adoptium.net/
# 设置环境变量
set JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-21.0.2.13-hotspot
set PATH=%JAVA_HOME%\bin;%PATH%
```

**Linux/macOS:**
```bash
# Ubuntu/Debian
sudo apt update
sudo apt install openjdk-21-jdk

# macOS (使用 Homebrew)
brew install openjdk@21

# 验证安装
java -version
javac -version
```

#### 2. 安装 Apache Maven

**Windows:**
```bash
# 下载 Maven: https://maven.apache.org/download.cgi
# 设置环境变量
set MAVEN_HOME=C:\maven\apache-maven-3.9.9
set PATH=%MAVEN_HOME%\bin;%PATH%
```

**Linux/macOS:**
```bash
# Ubuntu/Debian
sudo apt install maven

# macOS (使用 Homebrew)
brew install maven

# 验证安装
mvn -version
```

## 🔨 构建步骤

### 方法一：使用 Maven 命令行构建

```bash
cd ALCERecipeViewer

# 清理之前的构建文件
mvn clean

# 编译源代码
mvn compile

# 打包生成插件 JAR 文件（跳过测试）
mvn package -DskipTests
```

### 构建结果

构建成功后，在 `target/` 目录下生成：
- `ALCERecipeViewer-1.0.0.jar` - 主插件文件（含 FoliaLib shade）

### 本地快速构建命令

```bash
cd C:\Users\林子群\Desktop\ALCERecipeViewer
C:\maven\apache-maven-3.9.9\bin\mvn clean package -DskipTests
```

### 方法二：使用 IDE 构建

#### IntelliJ IDEA
1. 打开项目: `File` → `Open` → 选择项目文件夹
2. 等待 Maven 依赖自动下载
3. 构建: `Build` → `Build Project` (Ctrl+F9)
4. 或使用 Maven 面板: 双击 `Lifecycle` → `package`

#### Eclipse
1. 导入项目: `File` → `Import` → `Maven` → `Existing Maven Projects`
2. 选择项目文件夹
3. 右键项目 → `Run As` → `Maven build`
4. 目标输入: `clean package -DskipTests`

## 🔧 Maven 依赖说明

### 编译期依赖（provided，运行时由服务器提供）

| 依赖 | 版本 | 说明 |
|------|------|------|
| Spigot API | 1.21.3 | Bukkit/Spigot API |
| CraftEngine Core | 26.6 | CE 核心 API（编译期类型检查，运行时反射调用） |
| CraftEngine Bukkit | 26.6 | CE Bukkit 适配层 |
| Adventure MiniMessage | 4.18.0 | 文本格式化 |
| Adventure Plain Serializer | 4.18.0 | 纯文本序列化 |

### 编译期依赖（compile，shade 到插件中）

| 依赖 | 版本 | 说明 |
|------|------|------|
| FoliaLib | 0.5.2 | Folia/Paper/Spigot 调度器兼容层 |

### Shade 重定位

```
com.tcoded.folialib → com.linong.recipelookup.lib.folialib
```

## 🔧 常见构建问题

### 问题1: Java 版本不兼容
**解决方案:**
```bash
java -version
# 确保 Java 17+，推荐 21
```

### 问题2: CE 依赖找不到
CE 依赖来自 `https://repo.momirealms.net/releases/`，确保网络连通。

### 问题3: 内存不足
```bash
set MAVEN_OPTS=-Xmx2g -Xms512m
mvn clean package -DskipTests
```

### 问题4: 编码问题
```bash
mvn clean package -DskipTests -Dfile.encoding=UTF-8
```

## 📦 部署

```bash
# 将构建的 JAR 复制到服务器 plugins 目录
cp target/ALCERecipeViewer-1.0.0.jar /path/to/server/plugins/
```

## 📝 开发建议

- 遵循项目已有的代码风格和注释密度
- CE 反射调用使用 `ceLoader` 加载类，避免跨 ClassLoader 问题
- 新增菜单按钮使用 `menu.yml` action 系统，不需要改 Java 代码
- 文本字符串统一走 `lang.yml`，支持颜色码 `&`

---

**祝您构建成功！** 🎉
