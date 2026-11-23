# `:api` — 凱比用藥提醒的後端 API

這個模組定義凱比（Nuwa AIR-H300）會呼叫的所有後端 API。後端請照這裡實作；**和 `docs/後端接口文件.md` 不一致時，以這裡的程式碼為準。**

## 閱讀順序

1. **`docs/後端接口文件.md`：給後端工程師的完整說明（資料庫、13 支 API、後台操作、計算規則、背景工作）。從這份開始看。**
2. `src/main/kotlin/tw/kebbi/medreminder/api/`
   1. `KebbiApi.kt`：凱比會呼叫的 13 個 API。一個函式一個 endpoint，HTTP 方法和路徑寫在 Retrofit 註記上，註解寫什麼時候呼叫、會有哪些錯誤。
   2. `dto/`：Request、Response 的資料類別。**Kotlin 欄位名稱就是 JSON 欄位名稱**（camelCase）。列舉的 JSON 值看 `@SerialName`。
   3. `ApiConventions.kt`：Base URL、時間格式、header。
   4. `ApiError.kt`：錯誤格式（RFC 7807）、錯誤碼，以及凱比遇到每個錯誤怎麼處理。
   5. `Rules.kt`：提醒規則的固定常數。凱比和後端都要判定狀態，兩邊用同一份數字。
   6. `reference/`：給後端參考的計算邏輯（凱比的假後端也用這一份）：
      - `OccurrenceExpander`：處方＋作息 → 服藥事件（occurrence）和晚吃期限。
      - `PublishRules`：發佈新版本時，新舊 occurrence 怎麼合併（生效規則）。
      - `ChangeRequestRules`：凱比送出的變更申請怎麼套用。
      - `DoseStatusRules`：正式狀態、什麼時候發 LINE、凱比離線、App 不在前景。
   7. `AdminApi.kt`：後台中會影響凱比的操作（綁定、處方、作息、發佈、停用）。後台的其他 API 由後端自己設計。
3. `src/test/resources/samples/`：每個 API 的範例 JSON。`SamplesTest` 確認資料類別能解析這些範例；後端可以拿來比對自己的輸出。
4. `app/src/main/java/tw/kebbi/medreminder/sync/FakeBackend.kt`：凱比 App 裡的假後端，等於後端行為的簡化版。

## 怎麼跑測試

```
./gradlew :api:test
```

不需要 Android SDK 以外的東西，也不需要凱比。

## 如果後端也用 Kotlin 或 Java

可以直接引用這個模組（`dto/`、`Rules`、`reference/`），兩邊就不會對不起來。

## 問題怎麼回報

- 欄位、行為有疑問：在 GitHub 開 issue，標題寫 API 編號（例如「B1 occurrenceId 的格式」）。
- 要改 API：改這個模組的程式碼和範例 JSON，並在 `CHANGELOG.md` 記一筆（改了什麼、為什麼、哪一天）。
