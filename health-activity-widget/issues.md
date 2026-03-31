# Code Review Issues

## Critical

### 1. Day-of-week off-by-one — wrong 13-week window on Sundays
**Files:** `HealthConnectRepository.kt:202`, `GridRenderer.kt:43`

```kotlin
val todayDow = today.dayOfWeek.value % 7
```

`dayOfWeek.value` returns 1–7 (Mon–Sun). `Sunday (7) % 7 = 0` → `minusDays(0)` does not roll back to the week boundary. The data fetch window and the rendered grid are both shifted by one full day every Sunday. The bug is copy-pasted into both files.

---

### 2. Division by zero in `GridRenderer`
**File:** `GridRenderer.kt:46–48`

```kotlin
val cellW = (bitmapWidth - (weeks - 1) * gap) / weeks  // weeks not validated
val cellH = (bitmapHeight - (ROWS - 1) * gap) / ROWS
```

No guard on `weeks <= 0`. If `WEEKS` is ever misconfigured or a caller passes a bad value, this crashes with `ArithmeticException`.

---

### 3. Unchecked array index in `getActivityColor`
**File:** `WidgetPreferences.kt:53`

```kotlin
val color = PRESET_COLORS[index]  // index read from SharedPreferences
```

If SharedPreferences is corrupted or already contains an out-of-range value, this throws `IndexOutOfBoundsException`. The wraparound logic only prevents future corruption — it does not defend against a bad value already stored.

---

### 4. Unsafe cast crashes color picker
**File:** `ConfigActivity.kt:221`

```kotlin
val cur = v.tag as Int  // throws ClassCastException if tag is null or wrong type
```

Should use a safe cast: `v.tag as? Int ?: return`.

---

### 5. Unbounded bitmap allocation — OOM risk
**File:** `HealthWidgetProvider.kt:125–135`

```kotlin
return (options.getInt(...MAX_WIDTH, 250) * density).toInt().coerceAtLeast(200)
// No upper bound
```

On unusually large widget configurations or high-density displays, `Bitmap.createBitmap()` can allocate a very large ARGB_8888 buffer and throw `OutOfMemoryError`. Add a `coerceAtMost(MAX_REASONABLE_PX)`.

---

## Medium

### 6. Detached `CoroutineScope` — no lifecycle binding
**Files:** `HealthWidgetProvider.kt:37`, `ConfigActivity.kt:77, 107, 142, 156`

```kotlin
CoroutineScope(Dispatchers.IO).launch { ... }
```

These scopes are not attached to any lifecycle. Coroutines can outlive the widget provider or activity, wasting resources and risking UI updates on a destroyed activity. `HealthWidgetProvider` should use `goAsync()` with a managed scope; `ConfigActivity` should use `lifecycleScope`.

---

### 7. Race condition in color index assignment
**File:** `WidgetPreferences.kt:41–56`

The read-modify-write of `KEY_NEXT_COLOR_INDEX` is not atomic. Two coroutines querying a color simultaneously (e.g. during initial sync after permission grant) will read the same index and assign the same color to two different activity types.

---

### 8. Silent exception swallowing — no logging
**File:** `HealthConnectRepository.kt:136, 192`

```kotlin
} catch (_: Exception) { emptyList() }
```

Any Health Connect failure silently returns empty data. The widget goes blank with no indication of why. At minimum, log the exception to Logcat.

---

## Low

### 9. Dead import
**File:** `HealthWidgetProvider.kt:15`

`import kotlin.random.Random` is unused.

---

### 10. Suppressed deprecation with no comment
**File:** `ConfigActivity.kt:193`

`Switch` is deprecated. The suppression annotation has no comment explaining why `MaterialSwitch` was not used instead.

---

### 11. Magic number without explanation
**File:** `HealthWidgetProvider.kt:84`

```kotlin
PendingIntent.getActivity(context, appWidgetId + 10_000, ...)
```

The `10_000` offset prevents PendingIntent request code collisions but is undocumented. Extract to a named constant with a comment.

---

### 12. Hardcoded 10,000 step threshold
**File:** `HealthConnectRepository.kt:27`

```kotlin
private const val STEPS_THRESHOLD = 10_000L
```

Not user-configurable. Users with different activity levels or goals cannot adjust this value.
