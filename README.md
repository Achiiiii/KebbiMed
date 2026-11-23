# 用藥提醒 App（KebbiMed）

裝在凱比（Nuwa Kebbi **AIR-H300**）上的 Android App，提醒長者按時吃藥。畫面照 Figma「凱比介面｜用藥提醒」。

- **居家提醒**（長者）：時間到，凱比說話並顯示這包藥 → 按「吃了」花就長大 → 全部吃完開花。沒吃會再提醒一次；30 分鐘還沒吃記成「逾時未服」，在晚吃期限前還能補吃（記成「晚吃」），過了期限就是「漏服」。
- **設定**（右上角齒輪）：新增藥品（拍藥袋）、刪除藥品、吃藥時間（作息）。
  - **App 不會自己改提醒。** 這些操作都是送「變更申請」給後端，後端套用後，凱比再拉回新的排程。
- **後端**：排程由後端提供，凱比回報服藥事件。逾時、漏服、凱比離線的 LINE 通知由後端發，App 不直接發。
  - 後端由第三方依 `:api` 模組實作，還沒完成。在那之前 App 用內建的**假後端**。

| 文件 | 內容 |
|---|---|
| `docs/後端接口文件.md` | 給後端工程師：資料庫、13 支 API、計算規則、LINE 通知 |
| `docs/工程師交接.md` | 給接手的 App 工程師：架構、凱比實測到的限制、adb 指令、還沒做的 |

---

## 一、第一次建置

### 1. 準備
- **Android Studio**：到 https://developer.android.com/studio 下載安裝，用預設值就好。第一次開啟會下載 Android SDK。
- **JDK 17 或 21**。Android Studio 內建的 JDK 就可以；在命令列建置時，`JAVA_HOME` 要指到 JDK 17 或 21（**Java 24 編譯不過**）。
- **Nuwa SDK**（凱比原廠 SDK）：授權不允許放進公開 repo，要自己下載。
  1. 到 https://dss.nuwarobotics.com/ 註冊登入，下載 Nuwa SDK 的 `.aar`（目前用的是 2.1.0.10）。
  2. 放進 `app/libs/`。
  - **沒有放 aar 會編譯失敗。**

### 2. 打開專案
1. `git clone https://github.com/Achiiiii/KebbiMed.git`
2. Android Studio → **Open** → 選 `KebbiMed` 資料夾（裡面有 `settings.gradle.kts` 的那層）。
3. 等右下角的「Gradle sync」跑完。跳出「升級 Android Gradle Plugin」的提示時，按「Don't remind me」，不要升級（版本集中在 `gradle/libs.versions.toml`）。

命令列：

```
./gradlew :app:assembleDebug                  # 打包 debug 版
./gradlew :api:test :app:testDebugUnitTest    # 單元測試
./gradlew :app:lintDebug                      # lint
```

### 3. 在模擬器試跑
1. **Device Manager** → **Create Virtual Device** → 選 **Tablet**（例如 Pixel Tablet）→ 系統映像選 API 35、x86_64。
2. 按 ▶（Run）。畫面是橫向。
3. 依序看到：啟動動畫 →「用藥提醒」→ **配對畫面**（顯示配對碼和 QR Code），因為這台還沒綁定長者。
4. debug 版按「模擬後台綁定（測試）」，綁定假後端的「測試長者」，進入首頁。
5. 藥單一開始是空的：右上角齒輪 →「照服員設定 ›」→「假後端」→「後台加藥」或「2 分鐘後的測試提醒」。

- 模擬器上用 Android 內建語音說話；凱比上用 Nuwa SDK。
- 模擬器沒有藥袋可以拍，掃描畫面用「測試圖片」（見第三節）。

### 4. 裝到凱比上
1. 在凱比上打開**開發者選項**和 **USB 偵錯**。一般是「設定 → 關於 → 連點版本號碼 7 次」，再到「開發者選項」打開「USB 偵錯」。找不到入口的話，請洽 Nuwa（女媧創造）協助。
2. 用 USB 線接電腦和凱比，凱比上按「允許 USB 偵錯」。
3. 在 Android Studio 選凱比、按 ▶；或用命令列：

   ```
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   adb shell am start -n tw.kebbi.medreminder/.MainActivity
   ```

4. 第一次打開時允許通知，再完成配對（見上面第 3 步）。

在凱比上要知道的事：
- App 要一直開在畫面上（取代凱比臉）。在前景時會擋住電源鍵，凱比不會睡著。
- **重開機、按 Home 或 App 當掉後，凱比臉會蓋在最上面，App 不會自己回來**。要點通知，或用 RoFlow 打開 App。
- 這台凱比的系統關掉了通知彈出：App 不在前景時，通知只會進通知欄，提醒實際上只剩語音（要不要打開還沒決定）。

---

## 二、後端

- 後端網址在 `app/build.gradle.kts` 的 `API_BASE_URL`。**空的就用假後端**（`sync/FakeBackend.kt`），資料存在 App 自己的資料夾裡。
- 後端要實作的 API 在 `:api` 模組（`api/README.md`），完整說明在 `docs/後端接口文件.md`。
- 原型時期用的 Firebase 已經拿掉，`google-services.json` 不需要了。

debug 版的「照服員設定 → 假後端」可以模擬後台的操作和異常：

| 按鈕 | 做什麼 |
|---|---|
| 後台加藥／後台停藥 | 模擬照服員在後台改藥，凱比會說「站點照服員幫您更新了藥」 |
| 2 分鐘後的測試提醒 | 加一個 2 分鐘後要吃的藥 |
| 作息延後／提早 30 分 | 模擬後台改作息 |
| 模擬斷網、下一次回 401、下一次回 500 | 測離線補送和錯誤處理 |
| 停用／恢復凱比、解除綁定 | 測停用畫面和重新配對 |

---

## 三、怎麼測試

- **單元測試**：`./gradlew :api:test :app:testDebugUnitTest`。
- **在凱比上**：裝 debug 版，用下面的快轉時間和「假後端」測各種情況。
- **不想等時間**：照服員設定 →「快轉到下一次轉換前 10 秒」。只改 App 自己的時間，測完按「回到真實時間」。只有 debug 版有。
- **掃藥袋**：
  - 產生測試用的藥袋圖片（只有假資料）：`./gradlew :app:testDebugUnitTest --tests "*TestBagImageGenerator"`，圖片在 `app/build/test-bags/`。
  - 推到 `/sdcard/Android/data/tw.kebbi.medreminder/files/test/`，在掃描畫面按「測試圖片」（debug 版才有）。
  - `tools/test-qr-cards.html` 是原型時期的卡片，QR 內容是 `KEBBI:M13`，現在的掃描**不認得**這個格式，只認醫院藥袋的格式。

提醒規則（T ＝ 吃藥時間，常數在 `api/.../Rules.kt`，凱比和後端共用）：

| 時間 | 凱比會 |
|---|---|
| T 前 30 分鐘起 | 可以先按「吃了」，算準時 |
| T | 顯示這包藥並說話。App 不在前景時發通知，凱比照樣說話 |
| 按「等一下」 | 10 分鐘後再提醒，但不會晚過 T＋30；剩不到 2 分鐘時不顯示「等一下」 |
| T＋15 | 還沒吃，再提醒一次（畫面＋語音） |
| T＋30 | 記成**逾時未服**，說「現在吃也可以」。後端在 T＋32 發 LINE 給照服員 |
| 晚吃期限前 | 按「吃了」記成**晚吃** |
| 過了晚吃期限 | 記成**漏服**，不能再補吃（怕跟下一次太近） |
| 首頁 20 秒沒動 | 進待機畫面 |

- **晚吃期限**：到「下一次吃同一種藥」間隔的一半，至少 T＋30。一天一次的藥是 12 小時後，可以跨過半夜；每週一次的藥到當天 24:00。由後端算好放在排程裡。

---

## 四、程式在哪裡

| 位置 | 內容 |
|---|---|
| `api/` | 純 Kotlin 模組：凱比會呼叫的 13 支 API（`KebbiApi`）、資料類別、錯誤碼、規則常數（`Rules`）、給後端參考的計算邏輯（`reference/`）、範例 JSON |
| `app/.../data/` | `Repo`（唯一的資料來源）、Room 資料庫（occurrence、outbox）、`DeviceStore`、`Clock` |
| `app/.../engine/` | `ReminderEngine`（提醒的狀態機，純邏輯）、鬧鐘、開機重排、通知和通知上的按鈕、`Speech`（凱比要說的話） |
| `app/.../sync/` | `SyncManager`（只有這裡呼叫後端）、`SyncWorker`（背景心跳、補送）、`FakeBackend`、`FakeCatalog`（假的藥品目錄） |
| `app/.../robot/` | `RobotVoice`：凱比上用 Nuwa SDK 說話，模擬器用 Android 語音 |
| `app/.../scan/` | 掃藥袋：相機、QR（醫院藥袋格式 A、B）、OCR、比對藥品目錄 |
| `app/.../ui/` | Compose 畫面（`Screens.kt`）、`AppViewModel`、加藥精靈（`AddWizard.kt`） |

- 畫面以 Figma 的 1280×800 為基準，`ui/KebbiRoot.kt` 等比例縮放到螢幕。凱比 AIR-H300 是 1024×600（大約縮成 0.75 倍）。
- 畫面由狀態決定（例如有藥在提醒中，就顯示提醒畫面），不要改成用一次性事件跳頁。

---

## 五、還沒做的

1. **串接第三方後端**：後端完成後在 `API_BASE_URL` 填網址，照 `docs/後端接口文件.md` 第 9 節驗收。
2. **出貨準備**：正式簽章、`versionCode` 每版遞增、決定要不要混淆；和 Nuwa 確認用裝置管理安裝、更新；寫安裝與開機 SOP。
3. **要決定的事**：要不要打開凱比的通知彈出、後端文件第 10 節的待確認事項。
4. **還沒在凱比上測的**：真的醫院藥袋（Big5 編碼的 QR）、用 RoFlow 打開 App、沒有 QR 的藥袋倒數自動拍。
