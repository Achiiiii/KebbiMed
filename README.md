# 用藥提醒 App（KebbiMed）

照 Figma「凱比介面｜用藥提醒」做的 Android App，裝在凱比（Nuwa Kebbi）上。

- **站點設定**（照服員）：綁定長者 → 作息 → 一包一包掃藥袋 QR Code，或用問的選藥 → 設定完成
- **居家提醒**（長者）：依真實時間提醒吃藥 → 按「吃了」花就長大 → 全部吃完開花；沒吃會再提醒、判定漏吃、通知照服員
- **設定模式**（右上角「設定」）：調整時間、刪除藥品（要先問醫師）、新增一包藥
- **後台同步**：Firebase Firestore；照服員在後台改藥，凱比會自動更新並告訴長者

---

## 一、第一次打包（大約 1 小時，多半在等下載）

### 1. 安裝 Android Studio
到 https://developer.android.com/studio 下載安裝，一路按「Next」用預設值就好。第一次開啟會下載 Android SDK。

### 2. 打開專案
1. 解壓縮 `KebbiMed.zip`。
2. Android Studio → **Open** → 選 `KebbiMed` 資料夾（裡面有 `settings.gradle.kts` 的那層）。
3. 右下角會跑「Gradle sync」，第一次要下載很多東西，等它跑完。
   - 如果跳出「升級 Android Gradle Plugin」的提示，可以先按「Don't remind me」，不用升級。
   - 如果出現紅字錯誤，**把整段錯誤訊息複製給 Claude**。

### 3. 先在電腦上的模擬器試跑
1. 上方工具列 **Device Manager** → **Create Virtual Device** → 選 **Tablet** 類的（例如 Pixel Tablet）→ 系統映像選最新的 → Finish。
2. 按上方綠色 ▶（Run）。App 會在模擬器打開，畫面是橫向。
3. App 打開會先播啟動動畫，接著進入站點設定（作息 → 加藥）。不用輸入長者資料，預設是德高站林阿公（KB-0013）。

### 4. 裝到凱比上
1. 在凱比上打開 **開發者選項** 和 **USB 偵錯**：
   一般 Android 是「設定 → 關於 → 連點版本號碼 7 次」，再到「開發者選項」打開「USB 偵錯」。
   凱比的系統設定入口依機型、韌體不同，**如果找不到，請洽 Nuwa（女媧創造）協助開啟 USB 偵錯或安裝第三方 App 的權限**。
2. 用 USB 線接電腦和凱比，凱比上按「允許 USB 偵錯」。
3. Android Studio 上方裝置選單會出現凱比，按 ▶ 就會裝上去並打開。
4. 要給別人裝：選單 **Build → Build App Bundle(s) / APK(s) → Build APK(s)**，產生的 APK 在 `app/build/outputs/apk/debug/`。

> 還沒做第二步的 Firebase 也能打包和使用，只是不會同步到後台。

---

## 二、接上後台（Firebase，免費方案就夠）

1. 到 https://console.firebase.google.com → **新增專案**（名稱例如 `kebbi-med`）。
2. 專案首頁 → 新增應用程式 → **Android**：
   - Android 套件名稱填 **`tw.kebbi.medreminder`**（一定要一樣）
   - 下載 **`google-services.json`**，放到專案的 **`app/`** 資料夾裡（和 `app/build.gradle.kts` 同一層）
   - 後面的步驟（加 SDK）可以直接跳過，專案已經寫好了
3. 左側 **Build → Firestore Database → 建立資料庫**：地點選 `asia-east1（台灣）`，模式選「正式版」。
4. Firestore 的 **規則** 分頁：把 `firebase/firestore.rules` 的內容整個貼上 → 發布。
5. 左側 **Build → Authentication → 開始使用 → Sign-in method → 匿名（Anonymous）→ 啟用**。
6. 回 Android Studio：選單 **File → Sync Project with Gradle Files**，再按 ▶ 重新安裝。

裝好後，到 Firestore 的「資料」分頁會看到 `elders/KB-0013`，吃藥、漏吃、改藥都會記在 `events` 裡。

### 後台資料長這樣（給後台網頁、LINE 拉娃 APP 讀）
```
robots/{裝置編號}                       ← 每台凱比第一次開機會自動建立
  elderId: "KB-0013"  elderName: "林阿公"  stationId: "德高"  model: "..."  lastSeen: (時間)
  → 照服員在後台改 elderId，凱比就會換成照顧那位長者（裝置編號在 凱比「設定 → 照服員設定」看得到）

elders/KB-0013
  name: "林阿公"   stationId: "德高"
  meds: ["M13","M18","M07"]
  schedule: { breakfast:"08:00", lunch:"12:30", dinner:"18:30", sleep:"22:00" }
  today: { date:"2026-11-19", taken:1, total:3,
           slots: { "早餐後": {time:"08:00", status:"taken", takenAt:"08:03:12", meds:[...]}, ... } }
  flowers: 5
  updatedBy: "kebbi" | "caregiver"     ← 照服員在後台改藥時設成 "caregiver"，並更新 configUpdatedAt
  configUpdatedAt: 1763532000000

elders/KB-0013/events/{自動編號}
  type: "dose.taken" | "dose.missed" | "caregiver.notify" | "reminder.fire" | "reminder.snooze"
        | "reminder.repeat" | "med.add" | "med.remove" | "schedule.update" | "setup.done" | "day.rollover" ...
  text: "早餐後 吃了"   slot: "早餐後"   at: (伺服器時間)   source: "kebbi"
```
- **照服員改藥**：後台把 `meds`／`schedule` 改掉，同時寫 `updatedBy:"caregiver"`、`configUpdatedAt: 現在毫秒`。凱比收到後套用，並跟長者說「站點照服員幫您更新了藥…」。
- **漏吃通知**：後台和 LINE APP 監聽 `type == "caregiver.notify"` 的事件。
- 長者在凱比上新增或刪除藥的事件會帶 `needCaregiverConfirm: true`，後台要讓照服員確認（對應後台的「待確認的藥」）。

---

## 三、怎麼測試提醒

- **掃 QR Code**：用瀏覽器打開 `tools/test-qr-cards.html` 印出來，41 種藥各一張（內容是 `KEBBI:M13` 這種格式）。
- **不用等到吃藥時間**：右上角「設定」→ 最下面「照服員設定」→「測試：快轉到下一次提醒前 10 秒」。10 秒後會跳出提醒；測完按「回到真實時間」。
- 提醒規則（`engine/ReminderEngine.kt` 最上面的 `Rules` 可以改）：
  | 時間 | 凱比會 |
  |---|---|
  | T（吃藥時間） | 跳出提醒、說話 |
  | 按「等一下」 | 10 分鐘後再提醒 |
  | T + 15 分 | 還沒吃 → 再提醒一次 |
  | T + 30 分 | 判定漏吃 → 通知照服員 |
  | 首頁 20 秒沒動 | 進待機畫面 |

---

## 四、程式在哪裡

| 檔案 | 內容 |
|---|---|
| `data/MedDb.kt` | 41 種藥（與 Sheets 資料庫一致）、QR Code 解析 |
| `data/Models.kt` | 時段、作息（時段時間怎麼算）、服藥狀態 |
| `engine/ReminderEngine.kt` | 提醒規則（純邏輯，已用電腦測過） |
| `engine/AlarmScheduler.kt`、`AlarmReceiver.kt`、`BootReceiver.kt` | 準時叫醒 App、開機後重排 |
| `sync/CloudSync.kt` | Firebase 同步 |
| `robot/RobotVoice.kt` | 說話；用 Android 內建語音（手機有聲音；凱比上沒反應，凱比說話改由 RoFlow 負責） |
| `ui/Screens.kt` | 所有畫面（對應 Figma 代號 A1、B1…K0、C1…H9） |
| `ui/Components.kt`、`ui/Art.kt` | 說話列、大按鈕、花、藥丸、啟動動畫 |

畫面尺寸直接用 Figma 的數字（1280×800），`ui/KebbiRoot.kt` 會等比例縮放到凱比螢幕。

---

## 五、還沒做的（下一步）

1. **凱比本身的說話和動作**：由 RoFlow 負責，App 透過 MQTT 傳訊息給 RoFlow（見 docs/工程師交接.md 第 4 節）。
2. **醫院藥袋的 QR Code 格式**：各醫院不同，要拿合作醫院的藥袋來擴充 `MedDb.parseQr()`。
3. **後台網頁、LINE 拉娃 APP**：讀上面的 Firestore 資料。
4. **安全規則**：目前是原型規則（登入就能讀寫），正式上線前要依照服員帳號、站點限制。
5. 「拍照存起來」目前只提示照服員補登，還沒有真的拍照上傳。
