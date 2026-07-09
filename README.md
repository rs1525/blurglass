# LiquidGlass Library

A lightweight Android library that provides a real-time **Liquid Glass (frosted glass)** blur effect as a `FrameLayout`.

## Features

- **Real blur** — captures and blurs the content behind the view in real-time
- **Pure Kotlin** — uses StackBlur algorithm, no RenderScript or native dependencies
- **Works on all Android versions** (minSdk 24)
- **Configurable** — corner radius, blur radius, blur passes, outer shadow
- **Lightweight** — single file, zero external dependencies (only `androidx.core`)

## Setup

### Step 1 — Add JitPack repository

In your root `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
```

### Step 2 — Add the dependency

In your module `build.gradle.kts`:

```kotlin
dependencies {
    implementation("com.github.rs1525:blurglass:1.0.0")
}
```

## Usage

### XML

```xml
<com.akustom15.liquidglasslib.LiquidGlassView
    android:layout_width="match_parent"
    android:layout_height="200dp"
    android:layout_margin="16dp">

    <!-- Your content goes here -->
    <TextView
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_gravity="center"
        android:text="Glass Card"
        android:textColor="@android:color/white" />

</com.akustom15.liquidglasslib.LiquidGlassView>
```

### Programmatically

```kotlin
val glassView = LiquidGlassView(context).apply {
    cornerRadius = 50f
    blurRadius = 100
    blurPasses = 3
    addOuterShadow = true
}
```

## Properties

| Property | Type | Default | Description |
|---|---|---|---|
| `cornerRadius` | `Float` | `50f` | Corner radius for the rounded glass shape |
| `blurRadius` | `Int` | `100` | Blur strength (higher = more blur) |
| `blurPasses` | `Int` | `3` | Number of blur passes (more = smoother) |
| `addOuterShadow` | `Boolean` | `true` | Whether to draw a drop shadow |

## License

MIT License
