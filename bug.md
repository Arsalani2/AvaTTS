# 📋 bug.md — گزارش بررسی تخصصی باگ‌های AvaTTS (نسخه ۲)

> تاریخ بررسی: ۲۰۲۶-۱۰-۲۵ · روش: بازبینی ایستای (static review) خط‌به‌خط کل کدبیس Kotlin + تنظیمات بیلد + workflow روی شاخه `main`
> بدون کامپایل/اجرا؛ همه موارد «تأیید‌نشده تا زمان تست» هستند.
> هر باگ یک TODO دارد؛ بعد از فیکس تیک بزنید و خط «وضعیت تأیید» همان باگ را پر کنید.

## 🗂 سابقه بازبینی
- **نسخه ۱ (۲۰۲۶-۱۰-۲۵):** پوشش اولیه ۲۱ باگ.
- **نسخه ۲ (۲۰۲۶-۱۰-۲۵):** بررسی عمیق‌تر فایل‌های امنیتی (KeystoreHelper/VaultManager)، ادغام WAV، FileManager، Chunker، build.gradle و workflow.
  - ✏️ **BUG-18 اصلاح شد** (شمارش معکوس در واقع کار می‌کند؛ به نکته کارایی تبدیل شد) و **BUG-20 دقیق‌تر شد**.
  - ➕ ۹ باگ جدید: BUG-22 تا BUG-30 (کلید در APK، مرگ گاوصندوق بعد از backup/restore، کد مرده VaultScreen، انتخاب کلیدِ در کول‌داون، اسلایدر پلیر و…).
  - ✏️ BUG-04، BUG-06 و BUG-13 با شواهد جدید تقویت شدند.

## 🎯 خلاصه

| شدت | تعداد | شماره باگ‌ها |
|---|---|---|
| 🔴 بحرانی | ۴ | BUG-01 … BUG-04 |
| 🟠 بالا | ۸ | BUG-05 … BUG-10، BUG-22، BUG-23 |
| 🟡 متوسط | ۱۲ | BUG-11 … BUG-17، BUG-24 … BUG-28 |
| 🟢 جزئی | ۶ | BUG-18، BUG-19، BUG-20، BUG-21، BUG-29، BUG-30 |

توصیه ترتیب فیکس: BUG-03 و BUG-04 (خرابی منطقی در هر درخواست) → BUG-01/02 (پایداری پردازش پس‌زمینه) → BUG-23/22 (امنیت گاوصندوق و کلید) → BUG-05/06 (از دست رفتن داده) → مابقی.

---

## 🔴 بخش ۱ — بحرانی

### BUG-01 — صف تبدیل به چرخه حیات Activity گره خورده؛ بستن اپ، پردازش را می‌کشد
- [ ] TODO فیکس
- **فایل‌ها:** `MainViewModel.kt` (`onCleared()`)، `TtsQueueEngine.kt`، `TtsQueueService.kt`
- **شرح:** پردازش واقعی صف داخل `TtsQueueEngine` در scope اپ اجرا می‌شود؛ `TtsQueueService` فقط اعلان نمایشی است و منطق صف **داخل سرویس نیست**. `onCleared()` ویو‌مدل صریحاً `queueEngine.cancelQueue()` صدا می‌زند.
- **پیامد:** با بستن برنامه از Recents (یا finish شدن اکتیویتی) صف لغو می‌شود و چانک فعال وسط کار می‌ماند — دقیقاً خلاف هدف معماری «Foreground Service برای بَچ پس‌زمینه».
- **راه‌حل پیشنهادی:** انتقال مالکیت engine به داخل `TtsQueueService` (scope صف = scope سرویس)؛ حذف `cancelQueue()` از `onCleared()`؛ لغو فقط با اکشن کاربر یا پایان صف.

### BUG-02 — توقف Foreground Service از پس‌زمینه شکست می‌خورد؛ اعلان «در حال پردازش» گیر می‌کند
- [ ] TODO فیکس
- **فایل:** `TtsQueueService.kt` → `companion object fun stop(context)`
- **شرح:** `stop()` با `context.startService(intent با ACTION_STOP)` کار می‌کند؛ در اندروید ۸+ وقتی اپ پس‌زمینه است `startService` باعث `IllegalStateException` می‌شود که در try/catch بلعیده شده → ACTION_STOP هرگز به سرویس نمی‌رسد.
- **پیامد:** بعد از پایان صفِ طولانی، اعلان ongoing تا هارد‌ری‌استارت/سوایپ اپ باقی می‌ماند.
- **راه‌حل پیشنهادی:** `context.stopService(Intent(...))` یا bind و `stopSelf()` از داخل سرویس.

### BUG-03 — خطای شبکه مثل خطای کلید جریمه می‌شود؛ قطع اینترنت همه کلیدها را قفل موقت می‌کند
- [ ] TODO فیکس
- **فایل‌ها:** `TtsQueueEngine.kt` → `processChunkWithFailover()` (شاخه عمومی `catch`)، `MainViewModel.kt` → `testKey()`
- **شرح:** هر خطای غیر 429/403 — از جمله `TtsNetworkException` (قطعی اینترنت/timeout/DNS) — باعث `recordError(key)` با کول‌داون ۲۵ ثانیه‌ای و exclude شدن کلید می‌شود؛ در `testKey` هم همین رفتار است.
- **پیامد:** یک قطعی شبکه **همه کلیدها را به‌ترتیب وارد کول‌داون می‌کند**؛ بعد از وصل شدن اینترنت کاربر باید منتظر خروج کلیدها از کول‌داون بماند و آمار errorCount/stabilityScore بی‌دلیل خراب می‌شود.
- **راه‌حل پیشنهادی:** `TtsNetworkException` جدا catch شود (بدون recordError/exclude؛ فقط retry با backoff و پیام «اینترنت را بررسی کنید»)؛ قبل از هر تلاش `ConnectivityManager` چک شود.

### BUG-04 — مدل‌های ناموجود در زنجیره fallback و چیپ‌های پیشنهادی؛ هر خطای ۴۰۰ سه درخواست اضافه می‌سوزاند
- [ ] TODO فیکس
- **فایل‌ها:** `GeminiTtsService.kt` → `candidateModels`، `MainViewModel.kt` → `updateTtsModel()`، `SettingsScreen.kt` (چیپ‌های پیشنهادی مدل)
- **شرح:** لیست `candidateModels` شامل `gemini-3.8-flash-tts` و `gemini-3.5-flash` است که در API گوگل وجود ندارند (`3.5-flash` مدل TTS نیست و `responseModalities: AUDIO` قبول نمی‌کند). روی هر ۴۰۰/۴۰۴ — حتی ۴۰۰ ناشی از payload — هر سه مدل امتحان می‌شوند. `SettingsScreen` هم همین دو مدل را به‌عنوان چیپ قابل کلیک به کاربر پیشنهاد می‌دهد. پیش‌فرض `updateTtsModel` با ورودی خالی `gemini-3.8-flash-tts` است که با `UserPreferences` (`gemini-2.5-flash-preview-tts`) ناسازگار است.
- **پیامد:** تاخیر سه‌برابر و سوختن بلافایله سهمیه در هر چانک خطادار؛ کاربر با کلیک روی چیپ پیشنهادیِ خود اپ روی مدلی می‌افتد که همیشه 404 می‌دهد.
- **راه‌حل پیشنهادی:** محدود کردن زنجیره به مدل‌های واقعی TTS؛ تفکیک ۴۰۰ payload از ۴۰۰/۴۰۴ مدل؛ حذف چیپ‌های نامعتبر از تنظیمات؛ یکسان‌سازی پیش‌فرض‌ها.

---

## 🟠 بخش ۲ — بالا

### BUG-05 — شروع صف جدید، چانک PROCESSING قبلی را در وضعیت گیرانداخته رها می‌کند
- [ ] TODO فیکس
- **فایل:** `TtsQueueEngine.kt` → `startQueue()` (در مقایسه با `cancelQueue()`)
- **شرح:** `startQueue` بدون چک `isRunning`، `queueJob?.cancel()` می‌کند؛ ولی برخلاف `cancelQueue` استاتوس چانکِ فعال قبلی را به `STATUS_PENDING` برنمی‌گرداند.
- **پیامد:** چانک برای همیشه «در حال پردازش» می‌ماند؛ UI معمولاً جلوی شروع هم‌زمان را می‌گیرد ولی از مسیر برنامه‌ای ممکن است رخ دهد.
- **راه‌حل پیشنهادی:** در `startQueue` اگر صف در جریان است یا همان بازنشانی `cancelQueue` انجام شود یا با پیام رد شود.

### BUG-06 — بازتقسیم متن/حذف پروژه، تبدیل‌های انجام‌شده را پاک می‌کند و فایل‌های قدیمی orphan می‌مانند
- [ ] TODO فیکس
- **فایل‌ها:** `MainViewModel.kt` → `generateAndSaveChunks()`/`applyChunking()`/`deleteProject()`، `Daos.kt` → `replaceChunksForProject()`، `FileManager.kt` → `deleteAudioFile()` (موجود ولی **هیچ‌جا صدا زده نمی‌شود**)، `StudioScreen.kt` (دکمه ایمپورت .txt)
- **شرح:** `replaceChunksForProject` همه چانک‌ها (شامل COMPLETED با فایل صوتی) را DELETE و از نو می‌سازد؛ هیچ فایل wav معادل از دیسک حذف نمی‌شود. در تبدیل مجدد هم `audioPath` در DB نال می‌شود بدون حذف فایل. `deleteProject` هم فقط رکوردهای DB را پاک می‌کند و پوشه صوتی پروژه روی دیسک می‌ماند. در `StudioScreen` ایمپورت فایل .txt هم مستقیماً `updateCurrentText` + `applyChunking` را پشت‌سرهم صدا می‌زند.
- **پیامد:** (۱) با یک ویرایش/ایمپورت کوچک همه تبدیل‌های موفق بدون هیچ هشداری از دست می‌روند؛ (۲) نشت دائمی فضای ذخیره‌سازی (فایل‌های بدون مرجع بعد از بازتقسیم و حذف پروژه).
- **راه‌حل پیشنهادی:** فراخوانی `deleteAudioFile` قبل از حذف/بازسازی چانک و در `deleteProject` (پاک‌سازی پوشه پروژه)؛ هشدار قابل‌قبول با تعداد تبدیل‌هایی که از دست می‌روند؛ ترجیحاً حفظ چانک‌هایی که متنشان تغییر نکرده.

### BUG-07 — اگر رمزگذاری KeyStore شکست بخورد، کلید خام در SharedPreferences ذخیره می‌شود
- [ ] TODO فیکس
- **فایل‌ها:** `ApiKeyVaultManager.kt` → `addKey()`/`updateKey()`/`getDecryptedKey()`
- **شرح:** `KeystoreHelper.encrypt(...).ifBlank { trimmedKey }` — اگر encrypt با خطا «"» برگرداند کلید خام plaintext در SharedPreferences می‌رود؛ `getDecryptedKey` هم با `startsWith("AIzaSy")` همین حالت را تقویت می‌کند.
- **پیامد:** در دستگاه‌هایی که AndroidKeyStore موقتاً در دسترس نیست، وعده «ذخیره رمزگذاری‌شده AES-256-GCM» بی‌صدا نقض می‌شود.
- **راه‌حل پیشنهادی:** به‌جای fallback خام، خطا/هشدار صریح به کاربر («ذخیره امن در این دستگاه ممکن نیست») یا نگه‌داری با flag صریح plaintext + نمایش هشدار در UI گاوصندوق.

### BUG-08 — انتخابگر پوشه SAF نیمه‌کاره است؛ `storageTreeUri` ذخیره می‌شود ولی هیچ‌جا استفاده نمی‌شود
- [ ] TODO فیکس
- **فایل‌ها:** `SettingsScreen.kt` (`OpenDocumentTree` و `setStorageTreeUri`)، `FileManager.kt` → `getRootTtsDirectory()`
- **شرح:** کاربر پوشه مقصد را با SAF انتخاب می‌کند و URI در تنظیمات ذخیره می‌شود — اما `FileManager` فقط با مسیر خام به `Environment.getExternalStoragePublicDirectory(MUSIC/DOWNLOADS)` می‌نویسد و اصلاً به `storageTreeUri` نگاه نمی‌کند.
- **پیامد:** انتخاب هر پوشه‌ای (حتی SD کارت یا زیر‌پوشه دلخواه) بی‌اثر است؛ فقط «نام» پوشه بیرون کشیده می‌شود و فایل‌ها به Music/Downloads می‌روند.
- **راه‌حل پیشنهادی:** یا نوشتن واقعی با DocumentFile روی همان URI، یا حذف صریح انتخابگر برای جلوگیری از وعده غلط.

### BUG-09 — برای اندروید ۷/۸/۹ اجازه WRITE_EXTERNAL_STORAGE نیست؛ خروجی بی‌سروصدا به پوشه خصوصی اپ می‌افتد
- [ ] TODO فیکس
- **فایل‌ها:** `AndroidManifest.xml`، `FileManager.kt` → `getRootTtsDirectory()` (minSdk = 24)
- **شرح:** روی API ≤ ۲۸ نوشتن در دایرکتوری‌های عمومی بدون `WRITE_EXTERNAL_STORAGE` مجاز نیست؛ `mkdirs()` fail و زنجیره fallback به `getExternalFilesDir` می‌رسد بدون هیچ اطلاعی به کاربر که مسیر واقعی فرق کرده.
- **پیامد:** در اندروید ۷-۹ «فایل‌هایم کجا رفتند؟» — مسیر نمایشی تنظیمات با مسیر واقعی یکی نیست.
- **راه‌حل پیشنهادی:** افزودن `WRITE_EXTERNAL_STORAGE` با `maxSdkVersion="28"` + درخواست runtime؛ نمایش مسیر واقعی نهایی در UI.

### BUG-10 — مهاجرت دیتابیس ۱→۲ وجود ندارد؛ کاربران نسخه‌های اول crash می‌کنند
- [ ] TODO فیکس
- **فایل:** `AppDatabase.kt`
- **شرح:** فقط `MIGRATION_2_3` تعریف شده؛ کاربر دیتابیس نسخه ۱ هنگام ارتقا با `IllegalStateException: A migration from 1 to 3 was required but not found` در استارتاپ کرش می‌کند.
- **راه‌حل پیشنهادی:** افزودن `MIGRATION_1_2` یا تصمیم آگاهانه درباره destructive migration با در نظر گرفتن سیاست «بدون از دست دادن داده کاربر».

### BUG-22 — کلید تزریق‌شده از BuildConfig داخل APK بسته‌بندی می‌شود (تله امنیتی)
- [ ] TODO فیکس
- **فایل‌ها:** `ApiKeyVaultManager.kt` → `init` (seed از `BuildConfig.GEMINI_API_KEY`)، `app/build.gradle.kts` (پلاگین `secrets` با `propertiesFileName = ".env"`)، `.env.example`
- **شرح:** مقدار `GEMINI_API_KEY` از فایل `.env` به `BuildConfig` کامپایل می‌شود و در اولین اجرا به‌عنوان «کلید پیش‌فرض استودیو» seed می‌شود. `BuildConfig` داخل dex در APK حضور دارد و با ابزارهایی مثل apktool به‌سادگی استخراج می‌شود. فعلاً مقدار placeholder است و کد مقدار `MY_GEMINI_API_KEY` را نمی‌پذیرد، اما به محض اینکه یک کلید واقعی در `.env` (یا Secretهای محلی) گذاشته شود، با build ریلیز عمومی در APK منتشر می‌شود.
- **پیامد:** افشای کلید API تمام کاربران یک APK عمومی؛ محدودیت/لغو کلید توسط گوگل.
- **راه‌حل پیشنهادی:** حذف مسیر seed از BuildConfig (گاوصندوق خودش برای کلید هست) یا محدود کردن آن به buildType=`debug`؛ اگر لازم است، فقط از `local.properties`ِ غیرکامیت‌شده و صرفاً برای دیباگ.

### BUG-23 — قوانین Backup خالی است؛ بعد از Backup/Restore گاوصندوق بی‌صدا می‌میرد
- [ ] TODO فیکس
- **فایل‌ها:** `AndroidManifest.xml` (`allowBackup="true"`)، `res/xml/backup_rules.xml` و `data_extraction_rules.xml` (هر دو نمونه خالی)، `ApiKeyVaultManager.kt`
- **شرح:** `ava_vault_secure_store_v1` (ciphertext کلیدها) و Room DB به‌صورت پیش‌فرض در پشتیبان ابری/انتقال دستگاه قرار می‌گیرند؛ اما کلید AES در AndroidKeyStore **منتقل نمی‌شود**. روی دستگاه جدید `decrypt` برای همه کلیدها «"» برمی‌گرداند، `encryptedKey` هم با `AIzaSy` شروع نمی‌شود، پس `getDecryptedKey` نال می‌دهد.
- **پیامد:** بعد از restore، گاوصندوق ظاهر سالم دارد ولی همه کلیدها «نامعتبر» بی‌شمارند؛ کاربر دلیل را نمی‌فهمد و متن‌ها هم بدون پیام واضح fail می‌شوند.
- **راه‌حل پیشنهادی:** exclude کردن SharedPreferences گاوصندوق از `cloud-backup`/`device-transfer` (یا کل `.xml` مرتبط)؛ تشخیص حالت «کلید غیرقابل رمزگشایی» و نمایش پیام راهنما برای ورود مجدد کلیدها.

---

## 🟡 بخش ۳ — متوسط

### BUG-11 — SmartTextChunker نقطه اعشاری و اختصارات را جداکننده جمله می‌پندارد
- [ ] TODO فیکس
- **فایل:** `SmartTextChunker.kt` → `SENTENCE_DELIMITERS` (`'.'`, `':'`, `'!'`…)
- **شرح:** `'.'` به‌عنوان delimiter باعث برش «3.14» به «3.» + «14»، برش وسط URL/ایمیل/اختصارات و لحن بریده در TTS می‌شود. `':'` هم وسط ساعت (۱۲:۳۰) و نسبت‌ها می‌بُرد.
- **راه‌حل پیشنهادی:** نقطه/دونقطه فقط وقتی انتهای جمله حساب شود که بعدش فاصله یا انتهای متن باشد و کاراکتر قبلش رقم نباشد؛ برای علامت‌ها هم شرط فاصلهٔ بعد بگذارید.

### BUG-12 — ورودی «تعداد کاراکتر هر بخش» ارقام فارسی را قبول نمی‌کند و بدون محدوده است
- [ ] TODO فیکس
- **فایل‌ها:** `MainViewModel.kt` → `updateChunkSizeInput()`/`applyChunking()`، `StudioScreen.kt`
- **شرح:** `toIntOrNull()` روی «۵۰۰» نال می‌دهد (خلاف قاعده خود پروژه: همه ورودی‌های عددی ارقام فارسی/عربی را بپذیر)؛ مقدار ۰ یا ۵۰۰۰۰ هیچ‌جا clamp نمی‌شود → درخواست‌های خیلی بزرگ خطای ۴۰۰ می‌دهد و به BUG-04 گره می‌خورد.
- **راه‌حل پیشنهادی:** نرمال‌سازی ارقام فارسی/عربی + `coerceIn(100..5000)` + پیام مناسب.

### BUG-13 — همه `commit()`های SharedPreferences روی Main Thread
- [ ] TODO فیکس
- **فایل:** `UserPreferences.kt` (همه متدهای `update*`)، نمونه داغ: `SettingsScreen.kt` → فیلد «نام مدل صوتی» که `updateTtsModelName` را **در هر keystroke** صدا می‌زند
- **شرح:** `commit()` نوشتن همزمان روی دیسک است و مستقیم از onClickها روی Main Thread اجرا می‌شود؛ در فیلد نام مدل، به‌ازای هر کاراکتر تایپ‌شده یک write سنکرون انجام می‌شود.
- **پیامد:** jank روی دستگاه‌های کند و در بدترین حالت ANR.
- **راه‌حل پیشنهادی:** `apply()` به‌جای `commit()`؛ ذخیره فیلد نام مدل با debounce یا روی focus-loss/دکمه، نه در هر keystroke.

### BUG-14 — هر آپدیت پیشرفت یک `startForegroundService` اسپم می‌کند
- [ ] TODO فیکس
- **فایل:** `TtsQueueService.kt` → `updateProgress()`
- **شرح:** به‌جای `NotificationManager.notify` با همان ID، هر تیک اعلان یک سرویس با `startForegroundService` استارت می‌شود؛ روی اندروید ۱۲+ در پس‌زمینه `ForegroundServiceStartNotAllowedException` (catch شده) یعنی اعلان بی‌صدا آپدیت نمی‌شود؛ سربار سیستم هم بالاست.
- **راه‌حل پیشنهادی:** آپدیت مستقیم نوتیفیکیشن از داخل سرویس با `NotificationManager.notify(NOTIFICATION_ID_FOREGROUND, ...)`؛ start فقط یک‌بار.

### BUG-15 — استخراج مسیر از پیام لاگ با مسیرهای دارای فاصله می‌شکند
- [ ] TODO فیکس
- **فایل:** `LogsScreen.kt` → `extractPathFromMessage()`
- **شرح:** `Regex("(/[^\\s]+)")` با اولین فاصله قطع می‌شود؛ نام پروژه «کتاب صوتی» → مسیر ناقص → دکمه «باز کردن فایل» fail. ستون `filePath` در entity موجود است و فقط برای لاگ‌های قدیمی fallback لازم است.
- **راه‌حل پیشنهادی:** در fallback از انتهای رشته تا «ذخیره شد» برش بزنید؛ برای لاگ‌های جدید فقط `log.filePath`.

### BUG-16 — درخواست HTTP غیرقابل‌لغو؛ cancelQueue وسط درخواست را نمی‌بُرد
- [ ] TODO فیکس
- **فایل‌ها:** `GeminiTtsService.kt` (`execute()` بلاک‌کننده)، `TtsQueueEngine.kt` → `cancelQueue()`
- **شرح:** لغو کوروتین روی فراخوانی بلاک‌کننده OkHttp اثری ندارد؛ درخواست در جریان تا پایان کامل می‌شود (هدر سهمیه + رقابت با reset چانک به PENDING).
- **راه‌حل پیشنهادی:** `enqueue` + `suspendCancellableCoroutine` با `call.cancel()` در `invokeOnCancellation`.

### BUG-17 — رد شدن اجازه اعلان نادیده گرفته می‌شود؛ اعلان‌های سرویس بی‌صدا غایب‌اند
- [ ] TODO فیکس
- **فایل:** `MainActivity.kt` (کالبک خالی `notificationPermissionLauncher` با کامنت `/* Handled */`)
- **شرح:** در اندروید ۱۳+ با رد POST_NOTIFICATIONS، اعلان سرویس foreground و اعلان پایان تبدیل نمایش داده نمی‌شوند (نه خطا، نه توضیح) در حالی که سرویس کار می‌کند.
- **راه‌حل پیشنهادی:** پیگیری نتیجه؛ در رد شدن، یک بار توضیح + دکمه رفتن به تنظیمات سیستمی در Settings.

### BUG-24 — `VaultScreen.kt` (۶۳۲ خط) کد مرده است؛ دو UI موازی برای گاوصندوق
- [ ] TODO فیکس
- **فایل‌ها:** `VaultScreen.kt` (تابع `VaultScreen` تعریف شده ولی **هیچ‌جا صدا زده نمی‌شود**)، `MainActivity.kt` (import بلااستفاده + `vaultListState` hoist شده بلااستفاده)، `SettingsScreen.kt` (تب «گاوصندوق کلیدها» با پیاده‌سازی موازی `VaultKeyRowItem`)
- **شرح:** گاوصندوق واقعاً از داخل SettingsScreen مدیریت می‌شود؛ VaultScreen مستقل با Scaffold/FAB و ظاهر متفاوت هیچ مسیر ناوبری به آن ندارد. دو پیاده‌سازی موازی یعنی هر فیکس/قابلیت باید دوبار اعمال شود و با هم واگرا می‌شوند.
- **راه‌حل پیشنهادی:** حذف `VaultScreen.kt` و import/`vaultListState` مرده از MainActivity، یا برعکس: یک UI واحد و حذف کپی داخل Settings.

### BUG-25 — تب فعال با `remember` نگه‌داری می‌شود؛ با چرخش صفحه به استودیو برمی‌گردد
- [ ] TODO فیکس
- **فایل:** `MainActivity.kt` → `var currentTab by remember { mutableStateOf(AppTab.STUDIO) }`
- **شرح:** `remember` با بازسازی اکتیویتی (چرخش/تغییر دارک‌مود سیستمی) از بین می‌رود؛ در حالی که برای حالت‌های اسکرول از `rememberSaveable` استفاده شده، خود تب نه.
- **پیامد:** کاربر روی تب گزارش‌ها/تنظیمات است، گوشی را می‌چرخاند، به‌ناگهان در استودیو است (و اسکرول حفظ‌شده‌ی لیستِ تب دیگر حس ناهماهنگی می‌دهد).
- **راه‌حل پیشنهادی:** `rememberSaveable` با Saver enum (یا ذخیره در ViewModel).

### BUG-26 — اسلایدر پخش‌کننده در حین درگ با تیکر پیشرفت می‌جنگد
- [ ] TODO فیکس
- **فایل:** `AudioPlayerBar.kt` → `Slider(onValueChange = { onSeek(...) })`
- **شرح:** هر حرکت کوچک انگشت مستقیماً `seekTo` صدا می‌زند و هم‌زمان tickerِ ۲۰۰ms مقدار پوزیشن واقعی را روی slider بازمی‌نویسد؛ نتیجه: پرش thumb حین درگ، seekهای متوالی سنگین روی MediaPlayer و رفتار گیج‌کننده در RTL.
- **راه‌حل پیشنهادی:** state درگ محلی (`var dragPos by remember`)، فقط در `onValueChangeFinished` فراخوانی `onSeek`؛ در حین درگ پوزیشن محلی نمایش داده شود.

### BUG-27 — چیپ‌های «مدل پیشنهادی» در تنظیمات مدل‌های ناموجود را پیشنهاد می‌دهند
- [ ] TODO فیکس
- **فایل:** `SettingsScreen.kt` (لیست `suggestions` شامل `gemini-3.8-flash-tts` و `gemini-3.5-flash`)
- **شرح:** بخشی از BUG-04 ولی این‌جا اثر UX مستقیم دارد: کاربر با کلیک روی چیپ پیشنهادی خودِ اپ، مدل ناموجود را فعال می‌کند و هر تبدیل با 404 شروع می‌شود (بعد از دو fallback ناموفق به مدل سالم می‌رسد ولی با تاخیر و هزینه سه‌برابر).
- **راه‌حل پیشنهادی:** چیپ‌ها فقط مدل‌های واقعی TTS؛ اعتبارسنجی ورودی آزاد با یک درخواست سبک «تست مدل» قبل از ذخیره.

### BUG-28 — انتخاب کلید می‌تواند کلیدِ در حال خنک‌سازی را برگرداند و همان‌جا 429 تکراری بزند
- [ ] TODO فیکس
- **فایل:** `ApiKeyVaultManager.kt` → `selectOptimalKey()`/`selectRoundRobinKey()`، `TtsQueueEngine.kt`
- **شرح:** وقتی همه کلیدهای سالم exclude شده باشند (مثلاً بعد از یک خطای موقت)، `candidates` به کلیدهای کول‌داون‌دار برمی‌گردد و همان کلید در حال خنک‌سازی تحویل engine می‌شود؛ چون `areAllEnabledKeysInCooldown()` فقط وقتی «همه» در کول‌داون باشند صبر می‌کند، engine بی‌درنگ با کلیدِ خنک‌شونده درخواست می‌دهد → 429 دوباره → تمدید کول‌داون (حلقه هدررفت سهمیه).
- **راه‌حل پیشنهادی:** توابع انتخاب، کلید در کول‌داون را فقط برگردانند اگر هیچ گزینه سالمی نباشد و null هم معنا داشته باشد؛ engine قبل از تلاش `candidateKey.isCooldownActive` را چک کند و مثل حالت «همه در کول‌داون» عمل کند.

---

## 🟢 بخش ۴ — جزئی

### BUG-18 — ticker شمارش معکوس، کل لیست کلیدها را هر ثانیه بازترسیم می‌کند (نکته کارایی)
- [ ] TODO بهبود
- **فایل‌ها:** `VaultScreen.kt` و `SettingsScreen.kt` (`ticker` هر ۱ ثانیه به همه آیتم‌ها پاس داده می‌شود)
- **شرح:** شمارش معکوس درست کار می‌کند (تغییر پارامتر `ticker`، بازترسیم آیتم را اجباری می‌کند) ولی مکانیزمش سنگین است: هر ثانیه همه کارت‌های کلید (حتی سالم‌های بدون کول‌داون) recompose می‌شوند، چون مقدار از `System.currentTimeMillis()` خوانده می‌شود نه از state مشتق‌شده.
- **راه‌حل پیشنهادی:** ticker فقط به آیتم‌های `isCooldownActive` پاس شود؛ یا زمان باقی‌مانده در `ApiKeyItem` به‌صورت state محاسبه شود.

### BUG-19 — `RestartPromptDialog` هرگز نمایش داده نمی‌شود (کد مرده)
- [ ] TODO فیکس
- **فایل‌ها:** `MainViewModel.kt` (`_showRestartDialog` فقط false می‌شود، هیچ true-setter ندارد)، `RestartDialog.kt`
- **راه‌حل:** یا در تغییر theme/palette تریگر کنید، یا کد را حذف کنید.

### BUG-20 — `parseWav` با چانک‌های fmt غیراستاندارد به‌هم می‌ریزد
- [ ] TODO فیکس
- **فایل:** `WavAudioUtil.kt` → `parseWav()`
- **شرح:** (۱) اگر `chunkSize` بافر fmt کمتر از ۱۶ باشد، `ByteArray(chunkSize.toInt().coerceAtLeast(16))` تخصیص ۱۶ بایت می‌دهد و `readFully` **۱۶ بایت** می‌خواند (نه `chunkSize`) → ۴+ بایت از چانک بعدی خورده می‌شود و پارس کل فایل به‌هم می‌ریزد؛ (۲) چانک‌های با اندازه فرد (مثل fmt=۱۷) به‌همراه بایت pad، بدون رعایت word-alignment خوانده می‌شوند؛ (۳) `raf.skipBytes` تضمینی برای پرش کامل نیست و حلقه، پرشِ ناقص را چک نمی‌کند. خروجی فعلی Gemini استاندارد است ولی فایل‌های خارجی (مثل wavهای تبدیلی) ممکن است در merge رد شوند.
- **راه‌حل:** خواندن دقیقاً `chunkSize` بایت (با clamp امن)، رعایت pad برای اندازه‌های فرد، و حلقه پرش تا skip کامل.

### BUG-21 — چند مورد ریز (بسته‌بندی‌شده)
- [ ] TODO — `readTimeout = 60s` برای چانک‌های بلند ممکن است کم باشد؛ timeout هم مثل BUG-03 کلید را جریمه می‌کند.
- [ ] TODO — `AudioPlayerManager` بدون درخواست Audio Focus؛ پخش با موسیقی دستگاه هم‌زمان می‌افتد.
- [ ] TODO — `updateKey` با فقط تغییر نام هم `status = HEALTHY, cooldownUntil = 0` می‌کند؛ تغییر نام نباید کول‌داون کلید را صفر کند.
- [ ] TODO — مجوز `WAKE_LOCK` در manifest اعلان شده ولی هیچ‌جا request نمی‌شود (حذف یا استفاده واقعی برای صف طولانی).
- [ ] TODO — `FileManager.openFileOrFolder` در fallback از `Uri.fromFile` استفاده می‌کند (منسوخ در اندروید ۷+؛ استثنا catch می‌شود ولی رفتار نادرست)؛ باز کردن «پوشه» روی اندروید مدرن تقریباً همیشه به Toast مسیر ختم می‌شود چون FileProvider پوشه‌ها را پشتیبانی نمی‌کند — بهتر است مستقیم DocumentsUI (ACTION_VIEW با vnd.android.document/directory روی tree URI) باز شود.
- [ ] TODO — `context.getExternalFilesDir` می‌تواند نال برگرداند؛ `File(null, name)` مسیر نسبی می‌سازد (حالت حافظه unmounted).

### BUG-29 — FileProvider کل حافظه خارجی را در معرض اشتراک قرار می‌دهد
- [ ] TODO فیکس
- **فایل:** `res/xml/file_paths.xml` → `<external-path name="external_storage" path="." />`
- **شرح:** `path="."` روی ریشه external، اجازه می‌دهد content URI برای هر فایل عمومی دستگاه صادر شود. کاربرد فعلی فقط باز کردن wavهای خود اپ است، پس دامنه غیرضروری گسترده است.
- **راه‌حل پیشنهادی:** محدود کردن به `<external-path path="Music/..." />` و `<external-path path="Download/..." />` (یا rootهای دقیق‌تر).

### BUG-30 — بدهی بیلد: پلاگین‌ها/وابستگی‌های بلااستفاده و ناهماهنگی نسخه‌ها
- [ ] TODO پاک‌سازی
- **فایل‌ها:** `app/build.gradle.kts`، `gradle/libs.versions.toml`
- **شرح:** (۱) پلاگین `google-services` + وابستگی‌های firebase/googleid اعمال شده ولی `google-services.json` وجود ندارد (فعلاً فقط WARN می‌دهد)؛ (۲) `retrofit`/`moshi`/`coil`/`navigation-compose` در کد استفاده نمی‌شوند (OkHttp مستقیم به‌کار رفته) → APK حجیم‌تر و سطح حمله بیشتر؛ (۳) Compose BOM `2024.09.00` نسبت به `activityCompose 1.10.1` و Kotlin 2.2.10 قدیمی است (ناهماهنگی نسخه‌ها)؛ (۴) `versionCode = 1` هاردکد شده — نسخه‌دهی خودکار (مثل `version.properties` طبق الگوی بقیه پروژه‌ها) ندارد.
- **راه‌حل پیشنهادی:** حذف پلاگین و وابستگی‌های بلااستفاده؛ ارتقای BOM به نسخه هم‌رديف؛ بیرون‌کشیدن versionCode/versionName به فایل نسخه.

---

## 📌 وضعیت تأیید و قدم بعدی
- بازبینی ایستای کد است؛ هیچ موردی در بیلد/اجرا تست نشده.
- بعد از فیکس هر مورد: تیک TODO، افزودن خط «[تاریخ] فیکس شد + نحوه تأیید» زیر همان باگ، و کامیت با پیشوند `fix:` طبق CONTRIBUTING.md.
- پیشنهاد: قبل از شروع، اسکرین‌رکورد از رفتار فعلی BUG-01/02/03 و یک backup/restore دستی برای BUG-23 بگیرید تا بعد از فیکس مقایسه شود.
