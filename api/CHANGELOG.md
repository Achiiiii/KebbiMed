# API 修改紀錄

每次修改 `:api` 模組的 API（欄位、路徑、行為），都在這裡記一筆，並通知第三方後端。

## 2026-11-25：提醒參數改由後台設定

- **B1 `ScheduleDto`**：新增 `reminderSettings`（`ReminderSettingsDto`，可以沒有）：
  - `snoozeMinutes`：按「等一下」後幾分鐘再提醒（預設 10）。
  - `maxSnoozes`：同一包最多可以按幾次「等一下」，0 ＝ 不能按（預設 2）。之前凱比沒有次數限制。
  - `overdueAfterMinutes`：T 之後幾分鐘還沒吃就算逾時（預設 30）。
  - 照服員在後台改、發佈新版本後生效；凱比收到新版本時，正在提醒中的那包也改用新的值。沒帶就用 `Rules.kt` 的預設值。
- **後端要配合**：
  - 晚吃期限要 ≥ T＋`overdueAfterMinutes`。
  - 正式狀態（準時／晚吃）、逾時 LINE 的時間也要用同一個 `overdueAfterMinutes`。
  - 參考實作 `OccurrenceExpander.expand`、`DoseStatusRules.status`、`DoseStatusRules.overdueAlertDue` 多了 `overdueAfterMin` 參數（預設 30）。
- **範例**：`B1-schedule.response.json` 加上 `reminderSettings`。

## 2026-11-25：照片被拒收時不重試

- **R1 上傳照片**：後端回 4xx（例如 422 `INVALID_REQUEST`「照片的格式不對」）時，凱比**不再重試**，刪掉凱比上的照片，後面的照片繼續傳。
  - 之前會一直重試，而且卡住後面所有照片。
  - 不算拒收、之後會再傳的：401、403、408、409、429、5xx。413 照舊（照片留在凱比上，回報 `upload.too_large`）。
  - 判斷規則：`ErrorCodes.isUploadRejected`。
- **C3 操作紀錄**：新增 `type` = `upload.rejected`，`detail` 帶 `requestId`（對應 R2 的申請）、`status`（HTTP 狀態碼）、`code`（錯誤碼，有才帶）。後台可以用它說明「這筆申請為什麼沒有照片」。
  - 如果後端只接受固定的幾種 `type`，請加上這一種。

## 2026-11-23：加藥時一律問用法

- **R2 `PrescriptionDto`**（`add_medication` 的 `prescription`）：意思改成「長者在凱比上確認過的用法」，凱比一律會帶。
  - `dosePerTime`：`Int` 改成**小數**（`0.5` ＝ 半顆）。
  - `timesPerDay`：可以是 `0`（需要時才吃，沒有時段、不提醒）。
  - 新增 `mealRelation`：`BEFORE`（飯前）、`AFTER`（飯後）；一天 1 次時也可能是 `AT_BEDTIME`（睡前）、`ON_WAKE`（起床空腹）。沒帶就用目錄的設定。
  - 新增 `durationDays`：吃幾天，從後端套用那天算起（含當天，1–365）；`null` ＝ 一直吃。
  - `slots`：藥袋 QR 寫的時段，只在個數和 `timesPerDay` 一樣時才帶。
  - 時段怎麼排、數字不合理時回 422 的規則，見 `reference/ChangeRequestRules`。
- **B1 `MedicationDto`**：新增 `endDate`（吃到哪一天，含）。過了這天就不要再放進 `medications`。
- **後台 `MedicationOrderDto`**：新增 `asNeeded`（需要時才吃）。`endDate` 用在「吃幾天」。
- **加藥時已經在藥單裡**：吃完（過了 `endDate`）的同一個藥不算，換成新的。
- **參考實作**：`ChangeRequestRules.apply` 多一個參數 `today`（後端處理申請那天，台北時間）。

## 2026-11-23：第一版

和最初的 API 規劃相比，多了或改了這些：

- **B1 `ScheduleDto`**
  - 新增 `changeSource`（`initial`、`caregiver`、`device_request`）：凱比自己送出的申請不會再跟長者說「照服員更新了藥」。
  - `etag` 要包含排程視窗的第一天（例如 `"v7-2026-11-22"`）。排程視窗每天往後滾，凱比每天至少拉一次；版本沒變但日期變了，也要回新的內容。
- **B1 `MedicationDto`**：新增 `slots`、`customTimes`、`weeklyOn`，給畫面顯示「每天早餐後、晚餐後」。
- **B1 `OccurrenceItemDto`**：新增選填的 `catalogId`、`displayName`。藥被停掉、`medications` 裡已經沒有時，凱比還能顯示今天紀錄的藥名。
- **B2**：回應 `AckResponse { serverTime }`。
- **C1 `EventSource`**：新增 `system`（凱比依時間自己判定的提醒、逾時、漏服事件）。
- **C1 回應**：`scheduleVersion` 是這台凱比目前該用的排程版本。凱比上傳事件時順便比對，版本不同就重新拉 B1。
- **C2**：凱比本機沒有資料時（重灌、恢復啟用、重新綁定），會先用 C2 拿昨天和今天的狀態，再套用排程，避免已經吃過的又提醒一次。`status` 用 `taken`、`taken_late` 表示已吃。
- **E1 `CatalogDto`**：新增 `etag`、`categories`（選藥畫面的分類順序）；`defaultDose` 多了 `text`；`mealRelation` 的值是 `BEFORE`、`AFTER`、`ON_WAKE`、`AT_BEDTIME`、`ANY`。
- **錯誤碼**：新增 `INVALID_REQUEST`、`UNKNOWN_MEDICATION`、`PAIRING_CODE_INVALID`。
- **A1、A2、B2、C1、C3、R1、R2** 都帶 `Idempotency-Key` header。
