---
name: gen-test
description: 按 Navipilot 测试规范自动生成单元测试（JUnit 5 + Google Truth + MockK）
agent: atomcode
user_invocable: true
---

# Gen-Test — Navipilot 测试生成器

## 测试规范

遵循项目已有约定：
- **框架**: JUnit 5 + Google Truth (`assertThat`) + MockK
- **命名**: 反引号方法名 + 蛇形风格，描述 Given_When_Then
- **格式**: 方法内用 `// Given` / `// When` / `// Then` 注释分段
- **协程测试**: `kotlinx-coroutines-test`（`runTest` / `TestScope`）
- **文件位置**: `app/src/test/java/com/example/navipilot/`

## 使用方式

- `@gen-test "为 GeoUtils 生成测试"` — 读取 GeoUtils.kt 并生成对应测试
- `@gen-test "为 CoordinateConverter 生成测试，覆盖边界情况"` — 生成含边界值的测试
- `@gen-test "为 AmapBroadcastHandlers 生成测试，模拟高德广播 Intent"` — 生成广播解析测试

## 模板

```kotlin
package com.example.navipilot

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class `{ClassName}Test` {

    @Test
    fun `{methodName}_given_{condition}_when_{action}_then_{expected}`() {
        // Given
        val input = ...

        // When
        val result = {methodName}(input)

        // Then
        assertThat(result).isEqualTo(...)
    }
}
```
