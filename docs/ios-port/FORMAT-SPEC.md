# Locker 跨端格式规格

> 由 Android 端源码提取并经独立核查。Swift 实现必须逐条对齐。


## 日报二维码与密码学

**格式**：LK2 日报二维码（管道分隔的 11 字段文本）+ 身份码密码学信封（SHA-256 / PBKDF2-HMAC-SHA256 / HMAC-SHA256-16B / AES-256-GCM）


### 字段

| 路径 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `LK2.prefix` | String（字面量 "LK2"）→ Swift String | 是 | 第 0 字段。必须是大写 ASCII "LK2"，parse 用 == 精确比较，不接受小写或前后空格（整串 trim 后）。 |
| `LK2.salt` | String，32 个字符的小写十六进制 → Swift String | 是 | 第 1 字段 = identity.codeSalt（由 newSalt() 生成的 16 随机字节转小写 hex）。它同时是 PBKDF2 的 salt 来源（取其 UTF-8 字节）。parse 不校验格式，可为任意字符串；canonical 必须原样回填。 |
| `LK2.identityId` | String（UUID 文本）→ Swift String | 是 | 第 2 字段。parse 时 parts[2].ifEmpty { throw }，即只禁止空串；空白串能通过。对应 Room 表 sync_identities.identityId，值为 UUID.randomUUID().toString()（小写 36 字符）。不参与任何归一化，必须原样回填才能复现签名。 |
| `LK2.epochDay` | Long / Int64（十进制无填充）→ Swift Int64 | 是 | 第 3 字段。LocalDate.toEpochDay()，即「本地日历日期」距 1970-01-01 的天数，不是 epochMillis/86400000。解析用 Int64；溢出或非数字 → 抛「日报内容不完整」。注意 Kotlin toString() 无前导零、无正号。 |
| `LK2.number` | String（5 位数字）→ Swift String | 是 | 第 4 字段。由 generateFiveDigitNumber 生成，范围 10000–99999，无前导 0。但 parse 完全不校验：可为空串、可为任意文本。签名覆盖的是字符串原文，绝不可转 Int 再格式化（会吃掉前导零/改变原文）。 |
| `LK2.wearMinutes` | Long / Int64 → Swift Int64 | 是 | 第 5 字段。= 当日各佩戴分段裁剪到本地自然日后的毫秒总和 / 60_000 的整数除法（向下取整，Kotlin Long 除法向零截断，均为非负故等价）。未结束分段以 now 为终点。 |
| `LK2.eventCount` | Int / Int32 → Swift Int32 | 是 | 第 6 字段。parse 用 toIntOrNull()：超出 Int32 范围即视为非法。Swift 必须用 Int32 解析并在溢出时抛错，不能用 Int64 宽松接受。 |
| `LK2.photoCheck` | String，1 个字符，枚举 code → Swift enum 原始值 | 是 | 第 7 字段。取值集合严格为 {N, P, M, U}（大小写敏感）。PhotoCheck.from() 找不到即抛 IllegalArgumentException，不存在默认值或宽松回退。 |
| `LK2.photoFingerprint` | String，16 个字符的小写十六进制 → Swift String | 是 | 第 8 字段。= 当日照片 JPEG 的 SHA-256 小写 hex 字符串的前 16 个「字符」（即前 8 个摘要字节）。photoCheck == N（当日无照片）时为空串，parse 允许空串，canonical 中表现为「\|\|」双竖线，签名必须保留该空字段。 |
| `LK2.issuedAt` | Long / Int64（epoch 毫秒）→ Swift Int64 | 是 | 第 9 字段。System.currentTimeMillis()。是毫秒时间戳，与 epochDay（天）量纲不同，勿混用。 |
| `LK2.signature` | String，32 个字符的小写十六进制 → Swift String | 是 | 第 10 字段。HMAC-SHA256 输出取前 16 字节再转小写 hex（16 字节 = 32 hex 字符）。parse 只做存储、不校验长度；verify 用字符串相等比较。 |
| `canonical.separator` | String "|" → Swift Character | 是 | 单一竖线 U+007C，无空格。整个载荷共 11 个字段 = 10 个 canonical 字段 + 1 个签名字段，即 10 条竖线。 |
| `canonical.order` | 拼接顺序（不可重排） | 是 | canonical = PREFIX\|salt\|identityId\|epochDay\|number\|wearMinutes\|eventCount\|photoCheck.code\|photoFingerprint\|issuedAt（恰好 10 段，joinToString("\|")）。完整载荷 = canonical + "\|" + signature。签名只覆盖 canonical，不覆盖自身。 |
| `identityCode.alphabet` | String（31 个字符）→ Swift String | 是 | 生成用字符集 "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"：数字 2-9（8 个）+ 字母 A-Z 去掉 I、O（24 个）= 32？逐字为 2,3,4,5,6,7,8,9,A,B,C,D,E,F,G,H,J,K,L,M,N,P,Q,R,S,T,U,V,W,X,Y,Z 共 31 个。仅用于 generateCode；校验输入时不得据此拒绝（normalizeCode 不做字符集限制）。 |
| `identityCode.length` | Int = 8 → Swift Int | 是 | CODE_LENGTH = 8，生成 8 个字符（不含连字符）。 |
| `identityCode.displayFormat` | String 格式 "XXXX-XXXX" | 是 | generateCode = 8 字符 chunked(4).joinToString("-")，即 4-4 连字符。这只是展示格式；参与密码学的一律是去掉连字符后的 8 字符。 |
| `identityCode.normalize` | String -> String → Swift String | 是 | normalizeCode(input) = input.uppercase().filter { it.isLetterOrDigit() }。两步且顺序固定：先整串转大写，再逐字符保留 Unicode 字母或数字。连字符、空格、标点、下划线全部丢弃；大小写被抹平。Kotlin uppercase() 用 Locale.ROOT（与系统区域无关），Swift 用 .uppercased()（同样是 locale 无关的 Unicode 默认映射），不要用 localizedUppercase。注意全映射会改变长度（ß→SS），两端行为一致。 |
| `identityCode.saltBytes` | 16 随机字节 → 32 字符小写 hex → Swift String | 是 | newSalt() = SecureRandom 取 16 字节，按 %02x 逐字节转小写 hex，得到 32 字符。Swift 用 SecRandomCopyBytes(16) + String(format: "%02x")。绝不能把它解码回 16 字节再当 PBKDF2 salt（见 crypto.kdf）。 |
| `identityCode.codeHash` | SHA-256 → 64 字符小写 hex → Swift String | 是 | hashCode(salt, code) = SHA256( UTF8(salt) ‖ UTF8(normalizeCode(code)) )，两段直接拼接、无长度前缀、无分隔符。salt 是 32 字节 ASCII，code 是 8 字节 ASCII，总输入 40 字节。输出小写 hex 64 字符。用于 manifest.json 的 identity.codeHash，不写明文码。 |
| `crypto.kdf` | PBKDF2-HMAC-SHA256 → CommonCrypto CCKeyDerivationPBKDF | 是 | 参数：算法 PBKDF2WithHmacSHA256；迭代 120_000；输出 256 位 = 32 字节。password = UTF8(normalizeCode(code))，即 8 个 ASCII 字节、不含连字符（PBEKeySpec 传 char[]，JCE 内部按 UTF-8 编码）；salt = salt 十六进制字符串本身的 UTF-8 字节 = 32 字节，绝不是解码后的 16 字节。这一点已由 FixturesGenerator 明确记录（saltUtf8Bytes = 32）。 |
| `crypto.derivedKey` | 32 字节 → Swift SymmetricKey(data:) | 是 | deriveKey 返回 SecretKeySpec(pbkdf2Output, "AES")，即 32 字节 AES-256 密钥。同一份输出同时被复用为 HMAC-SHA256 的密钥。Swift 中一次派生，AES 与 HMAC 共用这 32 字节。 |
| `crypto.hmacSign` | HMAC-SHA256，截断 16 字节 → 32 字符小写 hex → Swift String | 是 | hmacSign(salt, code, data) = HMAC-SHA256(key = deriveKey(salt, code) 的 32 字节, message = UTF8(data))，取 digest.copyOf(16) 即前 16 字节，转小写 hex。密钥不是身份码本身、也不是 codeHash，而是 PBKDF2 输出。deriveKey 内部会再次 normalizeCode（幂等）。 |
| `crypto.iv` | 12 字节 → Swift AES.GCM.Nonce | 是 | IV_BYTES = 12。每次加密都用 SecureRandom 重新生成，不复用。Swift 用 AES.GCM.Nonce()（内部 SecRandomCopyBytes）或自备 12 字节。 |
| `crypto.gcmTag` | 128 位 = 16 字节 → CryptoKit 默认 | 是 | GCM_TAG_BITS = 128。CryptoKit 的 AES.GCM 默认就是 16 字节 tag，无需也无法改成别的长度，正好对齐。 |
| `crypto.encryptLayout` | ByteArray → Swift Data | 是 | encrypt 返回 iv + cipher.doFinal(plain) = IV(12) ‖ ciphertext ‖ tag(16)。AES/GCM/NoPadding，无 AAD（不传 additionalAuthenticatedData），因此 CryptoKit 也不要传 authenticating: 参数。CryptoKit 的 SealedBox.combined 恰好就是 nonce‖ciphertext‖tag，可直接互用。 |
| `crypto.decryptRule` | ByteArray -> ByteArray → Swift Data | 是 | decrypt 先 require(blob.size > 12)（严格大于，等于 12 也抛「加密数据不完整」），然后以 blob[0..<12] 作 IV、blob[12...] 作 ciphertext+tag。Swift：guard blob.count > 12 else throw；用 AES.GCM.SealedBox(combined:) 或手动切分 Nonce(data: blob.prefix(12)) + ciphertext + tag。同步包 payload.bin 使用同一信封：外层 zip 条目 payload.bin 的前 12 字节是 IV，其后是密文+tag。 |
| `photoFingerprint` | ByteArray -> String → Swift String | 是 | photoFingerprint(jpeg) = SHA256(jpeg 原始字节) → 小写 hex 字符串 → .take(16)。即取 hex 的「前 16 个字符」= 摘要前 8 个字节，不是前 16 个字节（不是 32 字符）。输入必须是磁盘上/包内的原始 JPEG 字节，不得先解码重编码（会改变字节与指纹）。 |
| `photoCheckCodes` | enum（4 个 case）→ Swift enum PhotoCheck: String | 是 | NONE = "N"（当日未上传照片，label「当日未上传照片」）、PASS = "P"（「✓ 照片号码核验通过」）、MISMATCH = "M"（「✗ 照片号码核验未通过」）、UNKNOWN = "U"（「未能识别照片中的号码」）。判定顺序（见 ViewModel）：候选包含当日号码 → PASS；候选为空 → UNKNOWN；否则 → MISMATCH；无照片 → NONE；OCR 抛异常 → UNKNOWN。码值大小写敏感，from() 无默认值。 |
| `ocr.candidateRegex` | Regex → Swift NSRegularExpression | 是 | Regex("\\d{5,}")，对整段 OCR 文本做 findAll。Java/Kotlin 的 \d 默认仅 ASCII [0-9]（未开启 UNICODE_CHARACTER_CLASS）；而 ICU/NSRegularExpression 的 \d 默认匹配所有 Unicode Nd 数字。Swift 必须写成 [0-9]{5,}，否则全角/阿拉伯-印度数字会产生 Android 不会产生的候选。 |
| `ocr.candidateOrder` | List<String> → Swift [String] | 是 | 每个匹配值调用 windowed(5)（step = 1，partialWindows = false）得到所有长度 5 的连续子串，再 distinct() 去重（保留首次出现顺序，等价于 LinkedHashSet），最后转 List。例："123456" → ["12345", "23456"]；"12345" → ["12345"]；"1234" → []（长度不足不产生匹配）。匹配按文本出现顺序处理，因此最终顺序 = 先按匹配位置、再按窗口起点。Swift 用 matches 顺序 + 内层滑动 + 有序去重。 |
| `syncArchive.payloadBin` | zip entry 布局 → Swift Data | 是 | 外层 zip = manifest.json（明文 UTF-8，可带 BOM，解析时剥离）+ payload.bin。payload.bin = IV(12) ‖ AES-256-GCM(内层 zip)，且该条目以 DEFLATED 0 级（仅存储不压缩）写出，因此 entry.size 可用于计算进度；解出的内层 zip 含 data.json（DEFLATE）+ photos/<epochDay>.jpg（STORED）。密钥与日报同源：deriveKey(manifest.identity.salt, 用户输入或已保存的 code)。 |

### 排序规则

LK2 载荷固定为 11 段、以单个 \"|\" 顺序拼接：LK2 | salt | identityId | epochDay | number | wearMinutes | eventCount | photoCheck | photoFingerprint | issuedAt | signature。前 10 段构成 canonical（签名对象），第 11 段是 canonical 的 HMAC。字段内部顺序不可调整、不可省略（空字段保留为空段，如无照片时 \"…|N||1700000000000|…\"），否则签名失效。OCR 候选列表的顺序为：按正则匹配在文本中出现的先后，再按每个匹配内部 windowed(5) 的起点先后，去重时保留首次出现。

### 边界条件与陷阱

- 【最容易踩的坑】PBKDF2 的 salt 是 32 字符十六进制字符串的 UTF-8 字节（32 字节），不是 hex 解码后的 16 字节。写成 Data(hexString:) 就会与 Android 完全不兼容，密钥全错。FixturesGenerator 已显式记录 saltUtf8Bytes = 32。
- PBKDF2 的 password 是 normalizeCode(code) 的 ASCII 字节（8 字节，无连字符），不是 "XXXX-XXXX" 带连字符的展示形式，也不是 salt‖code 的拼接。JCE 的 PBEKeySpec 收 char[] 并按 UTF-8 编码；因为归一化结果本应是 ASCII，所以 CommonCrypto 传 UTF-8 字节可对齐——但前提是只能出现 ASCII。
- HMAC 的密钥是 PBKDF2 输出的 32 字节，不是身份码本身、不是 codeHash、也不是 salt。HMAC 结果必须取「前 16 字节」再转 hex（32 个 hex 字符），不是取前 16 个 hex 字符。
- photoFingerprint 是「SHA-256 小写 hex 字符串的前 16 个字符」= 摘要前 8 个字节；与 hmacSign 的「前 16 字节」不是同一规则，两个截断长度别写反。
- Swift 的 \d 在 NSRegularExpression（ICU）里默认匹配 Unicode Nd，而 Kotlin/JVM 的 \d 默认只匹配 [0-9]。必须写成 [0-9]{5,}。
- CryptoKit 的 SealedBox.combined 布局恰好是 nonce(12)‖ciphertext‖tag(16)，与 Android 的 iv + doFinal 完全一致，可直接 Data 互转；但 Nonce(data:) 要求正好 12 字节，SealedBox(combined:) 也按 12 字节 nonce 切分，长度不对会直接抛错。
- AES-GCM 全程无 AAD。CryptoKit 调用时不要传 authenticating:，否则 tag 校验必然失败。
- 解密前必须 guard blob.count > 12（严格大于）。Kotlin 用 require，等于 12 也报「加密数据不完整」。
- 解析 LK2 必须保留空字段：Swift 用 payload.split(separator: "|", omittingEmptySubsequences: false)，并要求 count == 11。用默认的 split 会把空字段吞掉，导致 10 段或错位。
- 整串先 trim 再切分（Kotlin payload.trim()），但绝不可对各字段单独 trim——identityId/number/photoFingerprint 中的任何空白都属于被签名内容。
- canonical 必须由解析出的字段原样重新拼接（字符串直出），不能把 epochDay/wearMinutes 转 Int 再格式化、不能给 number 补前导零、不能重新生成 salt。任何重排/重格式化都会让验签失败。
- eventCount 用 Kotlin toIntOrNull()（32 位）。Swift 若用 Int64/Int 解析，会接受 Android 判为非法的超大值；应显式按 Int32 校验并抛「日报内容不完整」。
- number 与 photoFingerprint 允许为空串（photoCheck == N 时指纹必为空），identityId 只禁止空串（空白串可通过）。不要在 Swift 侧加更严格的非空/格式校验，否则会把 Android 生成的合法码判成非法。
- photoCheck 只接受 N/P/M/U 四个大写单字符，大小写敏感，无默认回退；未知值应抛错而不是降级为 UNKNOWN。
- normalizeCode 是「先 uppercase 再 filter(isLetterOrDigit)」，不是只删连字符和空格。它会保留中文、全角数字等任意 Unicode 字母/数字，并丢掉下划线、点、@ 等。Java 的 isDigit 只含 Nd，而 Swift 的 isNumber 还包含 Nl/No（如 ²、½）；若要逐字对齐，应判断 Unicode 属性 numericType == .decimal。
- uppercase 必须用区域无关的默认映射（Kotlin Locale.ROOT ↔ Swift .uppercased()）。不要用 localizedUppercase / uppercase(with:)，土耳其语等区域会把 i 映射成 İ 造成哈希不一致。注意全映射可能改变长度（ß→SS），两端一致即可，但不要在映射后再做 ASCII 过滤之类的小动作。
- 身份码字符集只用于生成（31 个字符：2-9 与 A-Z 去掉 I、O），normalizeCode 并不校验字符集。校验用户输入时不要因为含 0/O/1/I 就提前拒绝，否则与 Android 行为不同（Android 会照常算出哈希、只是不匹配）。
- generateCode 的 4-4 连字符只是展示；任何进入 crypto 的路径都必须先 normalizeCode。数据库中 identity.code 保存的是带连字符的展示形式，派生密钥前务必归一化。
- epochDay 是本地日期的 LocalDate.toEpochDay()（天），issuedAt 是 epoch 毫秒，量纲不同，不能互相换算。日报的「当日」范围按设备本地时区的自然日计算。
- 签名比较在 Android 是普通字符串相等（非常量时间），Swift 可用常量时间比较，但语义必须是「hex 全等」；签名长度 32 也应由验签自然失败而非额外前置校验来兜底（parse 本身不校验长度）。
- 照片指纹的输入必须是原始 JPEG 字节。Swift 侧若先 UIImage 解码再重编码，字节变化会导致指纹与 Android 不一致，进而使同步差异比较误报「照片内容被替换」。

### 来源

- `app/src/main/java/com/yxsdc/locker/data/SyncCrypto.kt:18`
- `app/src/main/java/com/yxsdc/locker/data/SyncCrypto.kt:19`
- `app/src/main/java/com/yxsdc/locker/data/SyncCrypto.kt:20`
- `app/src/main/java/com/yxsdc/locker/data/SyncCrypto.kt:21`
- `app/src/main/java/com/yxsdc/locker/data/SyncCrypto.kt:22`
- `app/src/main/java/com/yxsdc/locker/data/SyncCrypto.kt:26-31`
- `app/src/main/java/com/yxsdc/locker/data/SyncCrypto.kt:34-35`
- `app/src/main/java/com/yxsdc/locker/data/SyncCrypto.kt:37-41`
- `app/src/main/java/com/yxsdc/locker/data/SyncCrypto.kt:44-49`
- `app/src/main/java/com/yxsdc/locker/data/SyncCrypto.kt:51-52`
- `app/src/main/java/com/yxsdc/locker/data/SyncCrypto.kt:55-64`
- `app/src/main/java/com/yxsdc/locker/data/SyncCrypto.kt:67-73`
- `app/src/main/java/com/yxsdc/locker/data/SyncCrypto.kt:76-81`
- `app/src/main/java/com/yxsdc/locker/data/SyncCrypto.kt:83-88`
- `app/src/main/java/com/yxsdc/locker/data/SyncCrypto.kt:90-91`
- `app/src/main/java/com/yxsdc/locker/data/DailyReport.kt:5`
- `app/src/main/java/com/yxsdc/locker/data/DailyReport.kt:10`
- `app/src/main/java/com/yxsdc/locker/data/DailyReport.kt:12-22`
- `app/src/main/java/com/yxsdc/locker/data/DailyReport.kt:24-33`
- `app/src/main/java/com/yxsdc/locker/data/DailyReport.kt:41-44`
- `app/src/main/java/com/yxsdc/locker/data/DailyReport.kt:46-64`
- `app/src/main/java/com/yxsdc/locker/data/DailyReport.kt:66-68`
- `app/src/main/java/com/yxsdc/locker/data/DailyReport.kt:71-75`
- `app/src/main/java/com/yxsdc/locker/data/DailyReport.kt:77-89`
- `app/src/main/java/com/yxsdc/locker/data/DailyReport.kt:92-96`
- `app/src/main/java/com/yxsdc/locker/ui/LockerViewModel.kt:809-852`
- `app/src/main/java/com/yxsdc/locker/ui/LockerViewModel.kt:854-866`
- `app/src/main/java/com/yxsdc/locker/ui/LockerViewModel.kt:868-881`
- `app/src/main/java/com/yxsdc/locker/data/LockerRepository.kt:359-371`
- `app/src/main/java/com/yxsdc/locker/data/SyncArchive.kt:59-72`
- `app/src/main/java/com/yxsdc/locker/data/SyncArchive.kt:205-216`
- `app/src/test/java/com/yxsdc/locker/port/FixturesGenerator.kt:262-308`
- `app/src/test/java/com/yxsdc/locker/port/FixturesGenerator.kt:310-348`
- `app/src/test/java/com/yxsdc/locker/data/SyncCryptoTest.kt:10-33`
- `app/src/test/java/com/yxsdc/locker/data/DailyReportTest.kt:24-67`
- `app/src/test/java/com/yxsdc/locker/ui/components/QrRoundTripTest.kt:19-25`
- `app/src/main/java/com/yxsdc/locker/domain/RandomNumber.kt:8-9`
- `app/src/main/java/com/yxsdc/locker/ui/components/NumberOcr.kt:54-67`


## 数据库 schema

**格式**：Room/SQLite schema v8 → GRDB.swift（db 文件名 locker.db；7 张表，无任何 FOREIGN KEY 约束；实体 id 用 INTEGER PRIMARY KEY AUTOINCREMENT；时间统一 epoch millis，日期统一 epochDay）


### 字段

| 路径 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `persons.ddl` | SQL DDL | 是 | CREATE TABLE IF NOT EXISTS `persons` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `createdAt` INTEGER NOT NULL); 无二级索引。1→2 迁移里用 `INSERT INTO persons (id,name,createdAt) VALUES (1, '我', now)` 显式写入 id=1。 |
| `persons.id` | INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL → Int64 | 是 | Room 的 autoGenerate=true。Kotlin 侧 id=0 表示「未分配」，@Insert 会当作 NULL 自增；GRDB 里必须把 0 转成 nil 或用自定义 insert，否则会写入 id=0 的真实行。 |
| `persons.name` | TEXT NOT NULL → String | 是 | 默认人物「我」（DEFAULT_PERSON_NAME）。仓储层 trim 后非空且长度 ≤20；唯一性只在仓储层保证（读-判-写），数据库没有 UNIQUE 约束。 |
| `persons.createdAt` | INTEGER NOT NULL → Int64 (epoch millis) | 是 | Kotlin 默认值 System.currentTimeMillis()；迁移写入时也取当前时间。导入时保留备份里的值。 |
| `wear_segments.ddl` | SQL DDL | 是 | CREATE TABLE `wear_segments` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `personId` INTEGER NOT NULL, `startAt` INTEGER NOT NULL, `endAt` INTEGER); CREATE INDEX `index_wear_segments_personId_startAt` ON `wear_segments` (`personId`, `startAt`)。注意：单列索引 index_wear_segments_personId 在 6→7 迁移中已 DROP，v8 只有复合索引。 |
| `wear_segments.id` | INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL → Int64 | 是 | 同上，0 表示自增。@Update/@Delete 都按主键定位。 |
| `wear_segments.personId` | INTEGER NOT NULL → Int64 | 是 | 无外键，删除人物时由仓储层显式 DELETE。 |
| `wear_segments.startAt` | INTEGER NOT NULL → Int64 (epoch millis) | 是 | 区间查询左端点。所有查询 ORDER BY startAt ASC。 |
| `wear_segments.endAt` | INTEGER NULL → Int64? | 否 | NULL = 仍在佩戴（isActive）。SQL 里 NULL 参与比较结果为 NULL（false），所以重叠判断必须显式写 `endAt IS NULL OR endAt > :start`。同一人物理论上最多一条 NULL。 |
| `events.ddl` | SQL DDL | 是 | CREATE TABLE `events` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `personId` INTEGER NOT NULL, `type` TEXT NOT NULL, `occurredAt` INTEGER NOT NULL, `note` TEXT); CREATE INDEX `index_events_personId_occurredAt` ON `events` (`personId`, `occurredAt`)。单列索引 index_events_personId 在 6→7 已 DROP。 |
| `events.id` | INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL → Int64 | 是 | 0 表示自增；deleteEventById(id) 按此删除。 |
| `events.personId` | INTEGER NOT NULL → Int64 | 是 | 无外键。 |
| `events.type` | TEXT NOT NULL → String | 是 | 存 event_types.typeKey（内置 locked / unlocked，自定义为 `custom_<UUID>`）。写事件前仓储层校验 typeKey 存在，否则抛「事件类型不存在」。无外键。 |
| `events.occurredAt` | INTEGER NOT NULL → Int64 (epoch millis) | 是 | 区间查询 ORDER BY occurredAt ASC。注意两套区间语义见 edgeCases。 |
| `events.note` | INTEGER→TEXT NULL → String? | 否 | 写入前 `note?.takeIf { it.isNotBlank() }`：空白串归一为 NULL，但不 trim。读取时按 NULL 处理。 |
| `event_types.ddl` | SQL DDL | 是 | CREATE TABLE IF NOT EXISTS `event_types` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `typeKey` TEXT NOT NULL, `name` TEXT NOT NULL, `colorArgb` INTEGER NOT NULL, `sortOrder` INTEGER NOT NULL, `builtIn` INTEGER NOT NULL); CREATE UNIQUE INDEX `index_event_types_typeKey` ON `event_types` (`typeKey`)。 |
| `event_types.id` | INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL → Int64 | 是 | 排序第二键（sortOrder ASC, id ASC）。导入时 type.copy(id = 0) 重新分配。 |
| `event_types.typeKey` | TEXT NOT NULL → String | 是 | 唯一索引。内置固定值 locked / unlocked，不可变；自定义为 "custom_${UUID.randomUUID()}"。 |
| `event_types.name` | TEXT NOT NULL → String | 是 | 显示名。trim 后非空且 ≤20 字符；同名检查在仓储层（事务内读全表比较），无 DB 唯一约束。内置类型可改名。 |
| `event_types.colorArgb` | INTEGER NOT NULL → Int64（ARGB 0xAARRGGBB，可能 > Int32.max） | 是 | 写入时 `colorArgb or 0xFF000000` 强制不透明。默认 locked=0xFF2A78D6、unlocked=0xFFEB6834。Swift 用 Int64 存，转 Color 时截断到 UInt32。 |
| `event_types.sortOrder` | INTEGER NOT NULL → Int32 | 是 | 新增时 (max(sortOrder) ?: -1) + 1。查询 ORDER BY sortOrder ASC, id ASC。 |
| `event_types.builtIn` | INTEGER NOT NULL (0/1) → Bool | 是 | true 的内置类型禁止删除（仓储层 require(!builtIn)），删除自定义类型时先 DELETE 该类型全部 events 再删类型。 |
| `daily_photos.ddl` | SQL DDL | 是 | CREATE TABLE IF NOT EXISTS `daily_photos` (`personId` INTEGER NOT NULL, `epochDay` INTEGER NOT NULL, `jpeg` BLOB NOT NULL, `updatedAt` INTEGER NOT NULL, `fingerprint` TEXT NOT NULL DEFAULT '', PRIMARY KEY(`personId`, `epochDay`))。无二级索引（index_daily_photos_personId 在 6→7 已 DROP）。fingerprint 由 7→8 `ALTER TABLE daily_photos ADD COLUMN fingerprint TEXT NOT NULL DEFAULT ''` 追加，旧行全为 ''。 |
| `daily_photos.personId` | INTEGER NOT NULL → Int64（复合主键第 1 列） | 是 | GRDB 需声明 primaryKey = ["personId","epochDay"]。 |
| `daily_photos.epochDay` | INTEGER NOT NULL → Int64（复合主键第 2 列） | 是 | LocalDate.toEpochDay()，与时区无关的「日序号」。区间查询一律 BETWEEN 闭区间，ORDER BY epochDay ASC。 |
| `daily_photos.jpeg` | BLOB NOT NULL → Data | 是 | 整张 JPEG 存在库里。LENGTH(jpeg) 返回字节数，用于元信息与体积预估；hasDailyPhoto 用 EXISTS 避免读 BLOB。 |
| `daily_photos.updatedAt` | INTEGER NOT NULL → Int64 (epoch millis) | 是 | 每次 upsert 都置为 System.currentTimeMillis()。 |
| `daily_photos.fingerprint` | TEXT NOT NULL DEFAULT '' → String | 是 | = SHA-256(jpeg) 十六进制小写串的前 16 个字符（DailyReport.photoFingerprint）。写入时算好；旧数据为 '' 时在 photoMetaDetails 里按需补算并 upsert 回写。同步比对与打包只读它，不再读 BLOB。 |
| `daily_random_numbers.ddl` | SQL DDL | 是 | CREATE TABLE IF NOT EXISTS `daily_random_numbers` (`personId` INTEGER NOT NULL, `epochDay` INTEGER NOT NULL, `number` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`personId`, `epochDay`)); CREATE UNIQUE INDEX `index_daily_random_numbers_epochDay_number` ON `daily_random_numbers` (`epochDay`, `number`)。全局唯一索引保证「同一天不同人物号码不同」。 |
| `daily_random_numbers.personId` | INTEGER NOT NULL → Int64（复合主键第 1 列） | 是 | GRDB primaryKey = ["personId","epochDay"]；@Update 按这两列定位。 |
| `daily_random_numbers.epochDay` | INTEGER NOT NULL → Int64（复合主键第 2 列） | 是 | 区间查询 BETWEEN 闭区间，ORDER BY epochDay ASC；全表查询 ORDER BY epochDay ASC, personId ASC。 |
| `daily_random_numbers.number` | TEXT NOT NULL → String | 是 | 5 位数字字符串，范围 10000–99999（无前导 0）。生成用 SecureRandom.nextInt(90000)+10000。参与 (epochDay, number) 唯一索引。 |
| `daily_random_numbers.createdAt` | INTEGER NOT NULL → Int64 (epoch millis) | 是 | 生成时写入；同步导入时沿用来源值。 |
| `sync_identities.ddl` | SQL DDL | 是 | CREATE TABLE IF NOT EXISTS `sync_identities` (`identityId` TEXT NOT NULL, `personId` INTEGER NOT NULL, `displayName` TEXT NOT NULL, `code` TEXT NOT NULL, `codeSalt` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `lastExportedAt` INTEGER, PRIMARY KEY(`identityId`)); CREATE UNIQUE INDEX `index_sync_identities_personId` ON `sync_identities` (`personId`)。注意是 TEXT 主键、非自增。 |
| `sync_identities.identityId` | TEXT NOT NULL PRIMARY KEY → String | 是 | UUID 字符串。GRDB 需显式 primaryKey = "identityId"，不要按 rowid 处理。 |
| `sync_identities.personId` | INTEGER NOT NULL → Int64 | 是 | 有 UNIQUE 索引（一人一身份）。upsertSyncIdentity 用 INSERT OR REPLACE，换 identityId 但 personId 相同时会静默删掉旧身份行。 |
| `sync_identities.displayName` | TEXT NOT NULL → String | 是 | 创建时取 person.name；导入绑定时取 manifest.identityName。 |
| `sync_identities.code` | TEXT NOT NULL → String | 是 | 明文身份码，只存本机（zip 里只放哈希）。regenerateSyncCode 会连同 codeSalt 一起换新。 |
| `sync_identities.codeSalt` | TEXT NOT NULL → String | 是 | SyncCrypto.newSalt()。派生 HMAC 密钥，参与日报签名/校验。 |
| `sync_identities.createdAt` | INTEGER NOT NULL → Int64 (epoch millis) | 是 | 创建时 System.currentTimeMillis()。 |
| `sync_identities.lastExportedAt` | INTEGER NULL → Int64? | 否 | NULL = 从未导出。SyncRange.incrementalWindow 用它算增量起点（按系统时区取 LocalDate）。markIdentityExported 默认写 now。 |

### 排序规则

persons: ORDER BY id ASC；event_types: ORDER BY sortOrder ASC, id ASC；wear_segments: 所有区间查询与 getAllSegments 均 ORDER BY startAt ASC；events: 所有区间查询与 getAllEvents 均 ORDER BY occurredAt ASC；daily_photos: 所有区间查询 ORDER BY epochDay ASC；daily_random_numbers: 区间查询 ORDER BY epochDay ASC，全表 getAllDailyRandomNumbers 为 epochDay ASC, personId ASC。无 ORDER BY 的查询（getRandomNumbersForDay、getAllSyncIdentities、observeSegmentCount、observeEventCount、findNumberConflict、LIMIT 1 系列）顺序未定义，Swift 侧不得依赖。

### 边界条件与陷阱

- 【Flow → ValueObservation】Room 的每个 `fun observeX(): Flow<List<T>>` 在 GRDB 用 `ValueObservation.tracking { db in try T.fetchAll(db, sql) }`，仓储层再 `map(\{ it.firstOrNull() \})`。返回 `Flow<List<T>>` 而非 `Flow<T?>` 的四个查询（observeActiveSegment / observeDailyPhoto / observeDailyRandomNumber / LIMIT 1 系列）是 Room 的写法限制，Swift 可直接用 fetchOne 得到 Optional，但对外 API 语义应保持「0 或 1 条 → firstOrNull」，不要改成抛错。Room 的 Flow 首次订阅立即发一次、且任何相关表变更都会重发（即使结果相同，不做 distinct）；GRDB 的 ValueObservation 用 region 级跟踪，只在该查询实际读到的表/行变化时触发，语义更窄——若原逻辑依赖「同表其他行变化也刷新」（例如 observeActiveSegment 在插入任意 wear_segments 后重算），需显式加 `.tracking(regions:)` 或改为观察整表。
- 【两套事件区间语义不可统一】getEventsInRange(personId, from, to) 是 `occurredAt BETWEEN :from AND :to`，闭-闭；observeEventsInRange(personId, from, toExclusive) 是 `occurredAt >= :from AND occurredAt < :toExclusive`，闭-开。名字几乎一样但端点行为不同：调用方给 getEventsInRange 传的是当天最后一毫秒（toMillis = 次日零点 - 1），给 observeEventsInRange 传的是次日的零点（toExclusive）。Swift 里若把两者都写成同一个半开区间函数，月末/年末 23:59:59.999 的事件会漏或重复。
- 【分段重叠谓词必须逐字照抄】`startAt < :endExclusive AND (endAt IS NULL OR endAt > :start)`。常见写错：(a) 写成 `endAt >= :start`，会把「上一条正好在 start 时刻结束」的分段算成重叠，导致 startWearing 被误判冲突；(b) 写成 `startAt <= :endExclusive`，会把「下一条正好在 endExclusive 开始」的分段算进来，日统计多算一整天边界；(c) 省略 `endAt IS NULL`，因为 SQLite 里 `NULL > x` 是 NULL（视为 false），所有进行中的分段都会被漏掉，正在佩戴的时长归零。
- 【NULL 语义】endAt NULL 表示进行中/无穷远。validateSegment 用 `endExclusive = endAt ?: Long.MAX_VALUE`（Swift 用 Int64.max）把开区间转成半开区间；GRDB 里绑 Int64.max 没问题，但不要改写成 `endAt == nil OR endAt > start` 之外的任何形式，也不要给 endAt 设 DEFAULT。SQLite 的 `COALESCE(endAt, 9223372036854775807)` 语义等价但会破坏 (personId,startAt) 索引的部分利用。
- 【getSegmentsInRange 是 BETWEEN 且会「多带」】`startAt BETWEEN :from AND :to` 闭-闭，只按起点筛选：起点在窗口内、但结束时间远在窗口之后（甚至 endAt IS NULL）的分段也会被返回。它和 getSegmentsOverlapping 不是同义函数，不要互相替换。当前仓库里没有调用方（只有 DAO 声明），Swift 侧可保留但不要用它算时长。
- 【autoGenerate 与 0】Room 把 Kotlin 的 `id: Long = 0` 当作「未分配」在 @Insert 时替换为 NULL 自增。GRDB 的 PersistableRecord 会把 0 原样写入。必须把模型 id 声明为 `Int64?`（nil 时自增），或在 insert 前把 0 映射为 nil；importAll 里大量 `copy(id = 0)` 依赖这一行为，若直接照抄会写出 id=0 的重复主键行并让后续插入全部失败。
- 【AUTOINCREMENT 不是可选项】v8 所有自增主键都是 `INTEGER PRIMARY KEY AUTOINCREMENT`，会在 sqlite_sequence 里记录水位，删除行后 id 不复用。GRDB 里手写建表时必须保留 AUTOINCREMENT；写成普通 `INTEGER PRIMARY KEY` 会导致删除后 id 复用，导入/同步的本地 id 映射可能串行。
- 【INSERT OR REPLACE 的隐式删除】upsertDailyPhoto 与 upsertSyncIdentity 都是 `@Insert(onConflict = REPLACE)`。SQLite 的 REPLACE 是先 DELETE 冲突行再 INSERT：daily_photos 主键是 (personId, epochDay)，同键覆盖没问题；但 sync_identities 上还有 personId 的 UNIQUE 索引，若传入的 identityId 不同而 personId 相同，会删掉旧身份行（旧 identityId 与其 code 一起丢失），且不报错。GRDB 用 `save(db)` 或 `insert(db, onConflict: .replace)` 行为一致，需要按原样保留并在 UI 层理解这一副作用。
- 【INSERT OR IGNORE 的返回值】insertDailyRandomNumber 返回 Long，Room 在 IGNORE 命中时返回 -1，仓储靠 `inserted != -1L` 判断成功。GRDB 没有 -1 约定：应改用 `try record.insert(db, onConflict: .ignore)` 后检查 `db.changesCount == 1`（或 `SELECT changes()`），不能用 lastInsertRowid（它保留上一次成功插入的值，会误判成功）。
- 【ensureDailyRandomNumber 的完整重试语义】整体在一个写事务内：①先查 (personId, epochDay) 已存在则直接返回其 number；②`getRandomNumbersForDay(epochDay)` 取当天所有人物号码做 Set（跨人物去重）；③最多 20 次：SecureRandom 生成 10000–99999 的字符串，命中 taken 则 continue（注意 taken 不会在循环中更新，但因为成功即 break 所以无影响）；④INSERT OR IGNORE，成功即返回；⑤失败（-1）说明撞了唯一索引，再读一次自己的行，若已有值则返回它（处理并发下别的协程先插入的情形）；⑥20 次都失败抛 IllegalStateException("无法生成不重复的随机数")。Swift 必须用 `.immediate` 写事务复现串行化，否则「读 taken → 插入」之间会被另一个连接插入导致白白重试。
- 【setDailyPhoto 的未来日期校验】`require(epochDay <= LocalDate.now().toEpochDay())`，失败抛 IllegalArgumentException("不能上传未来日期的照片")。注意：用的是系统时区的 LocalDate.now()，且是「今天及以前」；同步导入路径（applySyncImport）不经过这个校验，只走 upsertDailyPhoto，因此导入未来日期照片是允许的——Swift 重写不要给导入路径也加上该校验，否则跨时区/设备时钟差会导致导入失败。
- 【epochDay 与 millis 混用】daily_photos / daily_random_numbers 用 epochDay（LocalDate.toEpochDay，UTC 天数，与时区无关）；wear_segments / events 用 epoch millis。窗口换算在 collectSyncExport / collectLocalRange：fromMillis = fromEpochDay 当天 00:00 系统时区；toMillis = (toEpochDay+1) 当天 00:00 - 1（即当天最后一毫秒）。分段查询传 (fromMillis, toMillis + 1)（半开），事件查询传 (fromMillis, toMillis)（闭闭），随机数/照片直接传 epochDay 闭区间。Swift 用 Calendar.current 换算时必须用同一时区与同一「零点」定义，且注意 DST 切换日一天不是 86400000 毫秒。
- 【fingerprint 的三个计算时机】(1) setDailyPhoto 写入时算 SHA-256 前 16 位 hex；(2) applySyncImport 时优先用来源 photo.fingerprint，为空才本地重算；(3) 旧数据（7→8 迁移补的 ''）在 photoMetaDetails 读取元信息时惰性补算并 upsert 回写。photoMetas 在照片行不存在时返回空串。Swift 侧哈希必须是小写 hex、取前 16 个字符（不是 16 字节），否则日报 photoFingerprint 与同步差异比对全部失配。
- 【事务与并发】所有多步写操作（addPerson / renamePerson / deletePerson / addEventType / updateEventType / deleteEventType / startWearing / stopWearing / addManualSegment / updateSegment / addEvent / updateEvent / exportAll / importAll / applySyncImport / ensureDailyRandomNumber）都在 `db.withTransaction` 内。Room 的写事务串行化；GRDB 用 `try db.inTransaction(.immediate)`（DatabaseQueue 也建议 immediate），否则 DEFERRED 事务的「先读后写」可能拿到 SQLITE_BUSY 或读到过期快照。
- 【分段唯一性只是应用层约束】validateSegment：先 require(endAt == null || endAt >= startAt)（零长度允许，端点为 start==end 的分段不算重叠，因为谓词用 `endAt > start`），再取与该区间相交的分段并 filter 掉自身 id，要求为空。数据库没有排斥约束，两条并发写入在无事务时都能成功。stopWearing 只校验 now >= startAt，不调用 validateSegment——若存在更晚的分段，把 endAt 设为 now 可能造成重叠，这是既有行为，Swift 重写应保持而非「顺手修好」。
- 【人物/事件类型重名只在仓储层】addPerson、renamePerson、addEventType、updateEventType 都是事务内「读全表 → 判重名 → 写」，数据库无 UNIQUE 索引。Swift 若改用 GRDB 的唯一索引来做，会改变错误类型与事务回滚范围，且导入时可能因历史重名数据直接失败。校验规则：trim 后非空、长度 ≤20（Kotlin String.length 是 UTF-16 码元数，Swift 的 count 是扩展字形簇，emoji 名称会得到不同结果）。
- 【deletePerson 的删除顺序与保护】要求至少保留一个人物（`getPersons().size > 1`），然后依次删 wear_segments、events、daily_photos、daily_random_numbers、sync_identities，最后删 persons。无外键级联，Swift 必须显式按此顺序；若开了 foreign_keys 且将来补了 FK，删除顺序也需保持。
- 【importAll 的破坏性语义】事务内先清空 events / wear_segments / daily_photos / daily_random_numbers / sync_identities / persons / event_types，再插入。注意：(a) 照片被清空且备份里不含照片，导入会永久丢失所有照片；(b) sync_identities 被清空且不重建；(c) 事件类型先插（id 重置为 0），persons 随后插入并建立 old→new id 映射，segments/events/randomNumbers 用 `idMap.getValue(personId)` 重映射——备份里若引用不存在的人物会直接抛异常并整体回滚；(d) `insertDailyRandomNumbers` 是普通 INSERT（无冲突策略），备份内部若有 (epochDay, number) 重复会让整个导入失败；(e) 若备份 persons 为空，后续 getValue 抛 NoSuchElementException。
- 【事件类型写入路径不走 upsert】insertEventType / insertEventTypes / updateEventType / deleteEventType 都是普通 @Insert/@Update/@Delete。插入 typeKey 重复会因唯一索引抛 SQLITE_CONSTRAINT（ABORT），事务回滚。applySyncImport 里先比对 existingTypes 再插入，属于应用层防重。
- 【note 归一化】所有写事件的路径（addEvent / updateEvent / applySyncImport）都用 `note?.takeIf { it.isNotBlank() }`：空白字符串（含全角空格）存 NULL，非空白原样保留不 trim。GRDB 写入时用 `note?.trimmingCharacters` 会改变数据，应只判断 `isEmpty/blank`。
- 【布尔与整数映射】event_types.builtIn、hasDailyPhoto 的 `SELECT EXISTS(...)` 都是 SQLite INTEGER 0/1。GRDB 的 Bool 是 DatabaseValueConvertible，可直接解码；但 EXISTS 查询在 GRDB 里应 `try Bool.fetchOne(db, sql:) ?? false`，不要用 `Int.fetchOne` 再 `!= 0` 混写导致类型不匹配。
- 【DailyPhotoMeta 投影列】getDailyPhotoMetas 只 SELECT epochDay, updatedAt, fingerprint, LENGTH(jpeg) AS byteSize，byteSize 是字节数（Int64），不是字符数；GRDB 用 `try Row.fetchAll` 或自定义 Decodable 结构映射。photoBytesInRange / syncExportEstimate 依赖它做体积预估，必须保留 LENGTH(jpeg) 而不要改成 `length(CAST(jpeg AS TEXT))`。
- 【单例与数据库文件】AppDatabase.get 是双重检查锁单例，库名 "locker.db"，未启用 fallbackToDestructiveMigration，未显式设置 journal mode（Room 默认 WAL）。Swift 侧用一个全局 DatabaseQueue（或 DatabasePool）单例，路径 Library/Application Support 下同名文件；若要与 Android 的备份文件互通，注意 WAL 模式下需 checkpoint 才能拷走完整数据。
- 【describeDay 格式】同步导入跳过冲突号码时返回 "M月d日"（Locale.CHINA，无前导零，如「3月5日」）。Swift 用 DateFormatter(dateFormat: "M月d日", locale: zh_CN) 并注意 epochDay 转 Date 需按系统时区当天零点。
- 【Flow 生命周期】仓储层把 persons / eventTypes 暴露为冷 Flow，ViewModel 用 stateIn(WhileSubscribed(5_000))。GRDB ValueObservation 同样要显式 start/取消，Swift 侧建议用 AsyncStream 包装并在 Task 取消时 stop observation，避免页面退出后仍持有数据库回调。

### 来源

- `c:/Users/yxsdc/Desktop/locker/app/src/main/java/com/yxsdc/locker/data/AppDatabase.kt:10-25 (version = 8, 7 entities)`
- `c:/Users/yxsdc/Desktop/locker/app/src/main/java/com/yxsdc/locker/data/AppDatabase.kt:30-79 (MIGRATION_1_2: persons 建表 + 默认人物 id=1 + wear_segments/events 重建并加 personId)`
- `c:/Users/yxsdc/Desktop/locker/app/src/main/java/com/yxsdc/locker/data/AppDatabase.kt:81-98 (MIGRATION_2_3: event_types + typeKey 唯一索引 + 内置类型)`
- `c:/Users/yxsdc/Desktop/locker/app/src/main/java/com/yxsdc/locker/data/AppDatabase.kt:100-115 (MIGRATION_3_4: daily_photos 复合主键)`
- `c:/Users/yxsdc/Desktop/locker/app/src/main/java/com/yxsdc/locker/data/AppDatabase.kt:117-133 (MIGRATION_4_5: daily_random_numbers + (epochDay,number) 唯一索引)`
- `c:/Users/yxsdc/Desktop/locker/app/src/main/java/com/yxsdc/locker/data/AppDatabase.kt:135-153 (MIGRATION_5_6: sync_identities + personId 唯一索引)`
- `c:/Users/yxsdc/Desktop/locker/app/src/main/java/com/yxsdc/locker/data/AppDatabase.kt:155-169 (MIGRATION_6_7: 复合索引替换单列索引)`
- `c:/Users/yxsdc/Desktop/locker/app/src/main/java/com/yxsdc/locker/data/AppDatabase.kt:171-178 (MIGRATION_7_8: daily_photos.fingerprint DEFAULT '')`
- `c:/Users/yxsdc/Desktop/locker/app/src/main/java/com/yxsdc/locker/data/AppDatabase.kt:180-206 (CREATE_DEFAULT_DATA: 默认人物「我」+ 两个内置事件类型)`
- `c:/Users/yxsdc/Desktop/locker/app/src/main/java/com/yxsdc/locker/data/AppDatabase.kt:208-227 (locker.db 单例, addMigrations 1→8)`
- `c:/Users/yxsdc/Desktop/locker/app/src/main/java/com/yxsdc/locker/data/Person.kt:6-16 (DEFAULT_PERSON_NAME = 我, autoGenerate id)`
- `c:/Users/yxsdc/Desktop/locker/app/src/main/java/com/yxsdc/locker/data/WearSegment.kt:7-19 (personId,startAt 索引, endAt nullable, isActive)`
- `c:/Users/yxsdc/Desktop/locker/app/src/main/java/com/yxsdc/locker/data/EventRecord.kt:7-17 (personId,occurredAt 索引)`
- `c:/Users/yxsdc/Desktop/locker/app/src/main/java/com/yxsdc/locker/data/EventTypeEntity.kt:10-21 (typeKey 唯一索引, builtIn)`
- `c:/Users/yxsdc/Desktop/locker/app/src/main/java/com/yxsdc/locker/data/DailyPhoto.kt:10-29 (复合主键, fingerprint, DailyPhotoMeta.byteSize)`
- `c:/Users/yxsdc/Desktop/locker/app/src/main/java/com/yxsdc/locker/data/DailyRandomNumber.kt:10-20 (复合主键 + (epochDay,number) 唯一索引)`
- `c:/Users/yxsdc/Desktop/locker/app/src/main/java/com/yxsdc/locker/data/SyncIdentity.kt:12-24 (identityId TEXT 主键, personId 唯一索引)`
- `c:/Users/yxsdc/Desktop/locker/app/src/main/java/com/yxsdc/locker/data/DefaultEventTypes.kt:3-31 (locked/锁射/0xFF2A78D6/0, unlocked/开锁射精/0xFFEB6834/1)`
- `c:/Users/yxsdc/Desktop/locker/app/src/main/java/com/yxsdc/locker/data/LockerDao.kt:31-41 (getActiveSegment / observeActiveSegment: endAt IS NULL, startAt DESC LIMIT 1)`
- `c:/Users/yxsdc/Desktop/locker/app/src/main/java/com/yxsdc/locker/data/LockerDao.kt:46-72 (getSegmentsInRange BETWEEN; getSegmentsOverlapping / observeSegmentsOverlapping 半开区间)`
- `c:/Users/yxsdc/Desktop/locker/app/src/main/java/com/yxsdc/locker/data/LockerDao.kt:83-97 (getEventsInRange 闭闭 vs observeEventsInRange 半开)`
- `c:/Users/yxsdc/Desktop/locker/app/src/main/java/com/yxsdc/locker/data/LockerDao.kt:102-148 (daily_photos 区间查询、getDailyPhotoMetas 的 LENGTH(jpeg)、hasDailyPhoto 的 EXISTS)`
- `c:/Users/yxsdc/Desktop/locker/app/src/main/java/com/yxsdc/locker/data/LockerDao.kt:150-151 (upsertDailyPhoto REPLACE)`
- `c:/Users/yxsdc/Desktop/locker/app/src/main/java/com/yxsdc/locker/data/LockerDao.kt:162-232 (daily_random_numbers 全部查询; insertDailyRandomNumber IGNORE)`
- `c:/Users/yxsdc/Desktop/locker/app/src/main/java/com/yxsdc/locker/data/LockerDao.kt:234-301 (event_types 排序; insert/update/delete 普通策略)`
- `c:/Users/yxsdc/Desktop/locker/app/src/main/java/com/yxsdc/locker/data/LockerDao.kt:303-319 (sync_identities 查询与 REPLACE upsert)`
- `c:/Users/yxsdc/Desktop/locker/app/src/main/java/com/yxsdc/locker/data/LockerRepository.kt:22-55 (Flow 暴露与 firstOrNull 映射)`
- `c:/Users/yxsdc/Desktop/locker/app/src/main/java/com/yxsdc/locker/data/LockerRepository.kt:57-90 (ensureDailyRandomNumber 20 次重试与冲突处理)`
- `c:/Users/yxsdc/Desktop/locker/app/src/main/java/com/yxsdc/locker/data/LockerRepository.kt:92-105 (setDailyPhoto 未来日期校验 + fingerprint)`
- `c:/Users/yxsdc/Desktop/locker/app/src/main/java/com/yxsdc/locker/data/LockerRepository.kt:111-141 (人物增删改名与级联删除顺序)`
- `c:/Users/yxsdc/Desktop/locker/app/src/main/java/com/yxsdc/locker/data/LockerRepository.kt:143-186 (事件类型增删改、颜色强制不透明、内置不可删)`
- `c:/Users/yxsdc/Desktop/locker/app/src/main/java/com/yxsdc/locker/data/LockerRepository.kt:190-241 (startWearing/stopWearing/手动分段与 validateSegment 重叠校验)`
- `c:/Users/yxsdc/Desktop/locker/app/src/main/java/com/yxsdc/locker/data/LockerRepository.kt:243-293 (事件增删改与类型存在性校验)`
- `c:/Users/yxsdc/Desktop/locker/app/src/main/java/com/yxsdc/locker/data/LockerRepository.kt:295-347 (exportAll / importAll 清空与 id 重映射)`
- `c:/Users/yxsdc/Desktop/locker/app/src/main/java/com/yxsdc/locker/data/LockerRepository.kt:349-434 (同步身份 ensure/regenerate/markExported/collectSyncExport/bind)`
- `c:/Users/yxsdc/Desktop/locker/app/src/main/java/com/yxsdc/locker/data/LockerRepository.kt:439-573 (applySyncImport 冲突跳过与 describeDay)`
- `c:/Users/yxsdc/Desktop/locker/app/src/main/java/com/yxsdc/locker/data/LockerRepository.kt:605-693 (photoMetaDetails 惰性补算 fingerprint、photoBytesInRange、collectLocalRange 窗口换算)`
- `c:/Users/yxsdc/Desktop/locker/app/src/main/java/com/yxsdc/locker/data/LockerRepository.kt:698-714 (名称归一化规则与 OPAQUE_ALPHA)`
- `c:/Users/yxsdc/Desktop/locker/app/src/main/java/com/yxsdc/locker/data/DailyReport.kt:70-75 (photoFingerprint = SHA-256 前 16 个 hex 字符)`
- `c:/Users/yxsdc/Desktop/locker/app/src/main/java/com/yxsdc/locker/domain/RandomNumber.kt:5-9 (generateFiveDigitNumber: nextInt(90000)+10000)`
- `c:/Users/yxsdc/Desktop/locker/app/src/main/java/com/yxsdc/locker/domain/SyncRange.kt:22-39 (窗口闭区间与增量窗口)`
- `c:/Users/yxsdc/Desktop/locker/app/src/main/java/com/yxsdc/locker/domain/Stats.kt:86-113 (区间裁剪 max/min 与半开边界, 供 Swift 对齐)`
- `c:/Users/yxsdc/Desktop/locker/app/src/main/java/com/yxsdc/locker/ui/LockerViewModel.kt:98-126 (observeSegmentsOverlapping / observeEventsInRange 的实际传参)`


## 领域逻辑

**格式**：Kotlin 领域逻辑规格（Stats.kt / TimeFormat.kt / SyncRange.kt / RandomNumber.kt）→ Swift 重写依据


### 字段

| 路径 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `StatsMode` | enum（Swift: enum StatsMode { case month, year }） | 是 | 只有 MONTH / YEAR 两个值。Kotlin 枚举常量名全大写，仅用于内部分支，不参与序列化。 |
| `WearSegment` | data class / Swift struct；id: Long=0, personId: Long, startAt: Long, endAt: Long?=null | 是 | startAt/endAt 是 epoch 毫秒。endAt == null 表示佩戴中（isActive 计算属性）。Swift 用 Int64 存时间戳，endAt 用 Int64?。 |
| `EventRecord` | data class / Swift struct；id: Long=0, personId: Long, type: String, occurredAt: Long, note: String?=null | 是 | 字段名是 type，值等于 EventTypeEntity.typeKey（不是显示名）。occurredAt 为 epoch 毫秒。note 可为 null，统计不使用。 |
| `EventTypeEntity` | data class / Swift struct；id: Long=0, typeKey: String, name: String, colorArgb: Long, sortOrder: Int, builtIn: Boolean | 是 | typeKey 唯一。colorArgb 是 32 位无符号 ARGB 但用 Kotlin Long 存储（0xFF......L），Swift 应用 UInt32。sortOrder 是 Kotlin Int（32 位），Swift 若持久化需保持 32 位范围。 |
| `SegmentDetail` | data class；segment: WearSegment, clippedMillis: Long, crossesPeriodBoundary: Boolean | 是 | clippedMillis = overlapEnd - overlapStart（仅在本期有正重叠时产生该明细）。 |
| `EventTypeStat` | data class；type: EventTypeEntity, count: Int, buckets: List<Int> | 是 | buckets 长度恒等于 bucketCount；count 是该类型在本期的总事件数（与 buckets 求和相等，除非出现 bucketIndex==-1 的防御分支）。 |
| `StatsResult` | data class；totalWearMillis: Long, eventStats: List<EventTypeStat>, wearBuckets: List<Long>, bucketLabels: List<String>, segmentDetails: List<SegmentDetail>, eventDetails: List<EventRecord> | 是 | totalWearMillis == wearBuckets.sum()。eventDetails 只包含本期事件；segmentDetails 只包含与本期有正重叠的分段。 |
| `bucketBoundaries(mode, anchor, zone)` | fun bucketBoundaries(mode: StatsMode, anchor: YearMonth, zone: ZoneId): List<Long>（epoch 毫秒，长度 = bucketCount + 1） | 是 | MONTH：对 day in 1..anchor.lengthOfMonth()，取 LocalDate(anchor.year, anchor.month, day).atStartOfDay(zone).toInstant().toEpochMilli()；再追加 end = anchor.plusMonths(1).atDay(1).atStartOfDay(zone) 的毫秒。YEAR：对 month in 1..12，取 YearMonth.of(anchor.year, month).atDay(1).atStartOfDay(zone)；再追加 end = YearMonth.of(anchor.year+1, 1).atDay(1).atStartOfDay(zone)。YEAR 模式完全忽略 anchor.month。边界都是本地时区的「当天最早时刻」，因此 bucketCount 为 28..31（MONTH）或 12（YEAR），且跨 DST 的桶宽度会是 23/24/25 小时。zone 无默认值，必须显式传。 |
| `bucketLabels(mode, anchor)` | fun bucketLabels(mode: StatsMode, anchor: YearMonth): List<String> | 是 | MONTH：1..anchor.lengthOfMonth() 的十进制字符串（不补零，如 "1".."31"），UI 层再拼「日」。YEAR：1..12 拼「月」，即 "1月".."12月"。长度与 bucketCount 相同。 |
| `computeStats` | fun computeStats(segments: List<WearSegment>, events: List<EventRecord>, eventTypes: List<EventTypeEntity>, mode: StatsMode, anchor: YearMonth, zone: ZoneId = ZoneId.systemDefault(), now: Long = System.currentTimeMillis()): StatsResult | 是 | 纯函数，不做 person 过滤（调用方只传当前人物的数据）。periodStart = boundaries.first()，periodEnd = boundaries.last()，bucketCount = boundaries.size - 1。now 只用于 endAt == null 的活动分段；每次调用应只取一次 now（UI 每 30 秒 tick 一次）。Swift 应把 zone/now 显式化，默认 TimeZone.current / Date()。 |
| `computeStats.segmentClipping` | 算法步骤 | 是 | 对每个 segment：segmentStart = segment.startAt；segmentEnd = (segment.endAt ?: now).coerceAtLeast(segmentStart)（若 endAt 早于 startAt 会被抬到 startAt，得到零长度）；overlapStart = max(segmentStart, periodStart)；overlapEnd = min(segmentEnd, periodEnd)。仅当 overlapEnd > overlapStart（严格大于）时：totalWear += overlapEnd - overlapStart，并生成 SegmentDetail(clippedMillis = overlapEnd - overlapStart)。完全落在期外或零长度的分段不产生任何明细。 |
| `computeStats.bucketAccumulation` | 算法步骤 | 是 | 对每个有正重叠的分段，遍历 index in 0 until bucketCount：bucketStart = max(overlapStart, boundaries[index])；bucketEnd = min(overlapEnd, boundaries[index+1])；若 bucketEnd > bucketStart 则 wearBuckets[index] += bucketEnd - bucketStart。O(分段数 × 桶数)，因为桶区间互不重叠，结果与二分查找等价。边界值恰好落在桶起点时归入右侧桶（半开区间）。 |
| `computeStats.crossesPeriodBoundary` | 算法步骤 | 是 | crossesPeriodBoundary = segmentStart < periodStart \|\| segmentEnd > periodEnd。使用未裁剪的 segmentStart 和已钳制的 segmentEnd（活动分段用 now）。严格不等号：起点恰好等于 periodStart、或终点恰好等于 periodEnd 都算 false。活动分段只要 now 在本期内就为 false。 |
| `computeStats.eventFiltering` | 算法步骤 | 是 | periodEvents = events.filter { it.occurredAt in periodStart until periodEnd }，即半开区间 [periodStart, periodEnd)。恰好等于 periodEnd 的事件被排除，恰好等于 periodStart 的包含。 |
| `computeStats.eventBucketing` | 算法步骤 | 是 | 对每个 periodEvent：countsByType[event.type] = (旧值 ?: 0) + 1；index = bucketIndex(boundaries, occurredAt)；若 index >= 0 则 bucketsByType[event.type][index] += 1。注意：index == -1 时 count 仍然加一，只是不进桶（保留这个不对称行为）。event.type 即 typeKey。 |
| `computeStats.typeOrdering` | 算法步骤 | 是 | orderedTypes = eventTypes.sortedWith(compareBy({ it.sortOrder }, { it.id }))，即先按 sortOrder 升序、再按 id 升序，Kotlin 的排序是稳定的。随后未知类型被「追加」到列表末尾，不会重新参与排序。因此若某个已知类型的 sortOrder == Int.MAX_VALUE，它仍排在未知类型之前——不能在追加后再整体排序。 |
| `computeStats.unknownFallback` | 算法步骤 | 是 | unknownKeys = countsByType 的键中不属于 { eventTypes.typeKey } 的集合，用 Kotlin String 自然序（UTF-16 code unit 字典序，非本地化排序）升序排列。对每个 key 追加 EventTypeEntity(typeKey = key, name = "未知事件", colorArgb = EventColors.SLATE = 0xFF6B7280 = 4285231744, sortOrder = Int.MAX_VALUE = 2147483647, builtIn = false, id = 0)。未知类型必然 count >= 1。 |
| `computeStats.eventStats` | 算法步骤 | 是 | orderedTypes.map { EventTypeStat(type = it, count = countsByType[it.typeKey] ?: 0, buckets = (bucketsByType[it.typeKey] ?: IntArray(bucketCount)).toList()) }。所有已声明类型即使本期 0 事件也要出现在结果里，buckets 是全 0 的长度 bucketCount 数组（不要过滤空行）。eventTypes 中若有重复 typeKey，会输出多行共享同一份计数。 |
| `computeStats.resultSorting` | 算法步骤 | 是 | segmentDetails = details.sortedByDescending { it.segment.startAt }（稳定；startAt 相同时保持输入顺序）；eventDetails = periodEvents.sortedByDescending { it.occurredAt }（稳定）。wearBuckets 按时间升序；bucketLabels 来自 bucketLabels(mode, anchor)。 |
| `bucketIndex` | private fun bucketIndex(boundaries: List<Long>, time: Long): Int | 是 | 从 i = 0 到 boundaries.size-2 线性扫描，返回第一个满足 time >= boundaries[i] && time < boundaries[i+1] 的 i；没有则返回 -1。区间半开、左闭右开。 |
| `formatDateTime` | fun formatDateTime(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String | 是 | 模式串 "yyyy-MM-dd HH:mm"，Locale.CHINA。24 小时制、月/日/时/分补零。例：Asia/Shanghai 下输出 "2026-11-05 09:07"。Swift 用 DateFormatter，dateFormat = "yyyy-MM-dd HH:mm"，locale = en_US_POSIX，calendar = gregorian，timeZone = 传入 zone。 |
| `formatDate` | fun formatDate(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String | 是 | 模式串 "yyyy年M月d日"，Locale.CHINA。月和日不补零。例："2026年11月5日"。Swift 必须给 CJK 字符加引号："yyyy'年'M'月'd'日'"。 |
| `formatDateCompact` | fun formatDateCompact(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String | 是 | 模式串 "yyyy-MM-dd"，Locale.CHINA。月和日补零。例："2026-11-05"。 |
| `formatTime` | fun formatTime(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String | 是 | 模式串 "HH:mm"，Locale.CHINA。24 小时制、补零。例："09:07"。注意 Java 的 HH 是 0..23，不是 hh（12 小时制）。 |
| `formatMonth` | fun formatMonth(anchor: YearMonth): String | 是 | 直接字符串拼接 "${anchor.year}年${anchor.monthValue}月"，无 zone、无补零。例：YearMonth(2026, 11) → "2026年11月"；YearMonth(2026, 1) → "2026年1月"。 |
| `formatDuration` | fun formatDuration(millis: Long): String | 是 | totalMinutes = millis.coerceAtLeast(0) / 60_000（先钳到 0 再整除，等价 floor）；days = totalMinutes / 1440；hours = (totalMinutes % 1440) / 60；minutes = totalMinutes % 60。分支：days > 0 → "{days} 天 {hours} 小时 {minutes} 分"；否则 hours > 0 → "{hours} 小时 {minutes} 分"；否则 "{minutes} 分"。注意数字与单位之间、各段之间都有半角空格。示例：0→"0 分"；59_999→"0 分"；60_000→"1 分"；3_599_999→"59 分"；3_600_000→"1 小时 0 分"；86_339_999→"23 小时 59 分"；86_400_000→"1 天 0 小时 0 分"；90_061_000→"1 天 1 小时 1 分"；负数→"0 分"。 |
| `formatDurationWithSeconds` | fun formatDurationWithSeconds(millis: Long): String | 是 | totalSeconds = millis.coerceAtLeast(0) / 1000；days = totalSeconds / 86_400；hours = (totalSeconds % 86_400) / 3_600；minutes = (totalSeconds % 3_600) / 60；seconds = totalSeconds % 60；clock = String.format(Locale.ROOT, "%02d:%02d:%02d", hours, minutes, seconds)。days > 0 → "{days}天 {clock}"（天与时间之间一个半角空格）；否则只返回 clock。示例：0→"00:00:00"；999→"00:00:00"；1000→"00:00:01"；3_661_000→"01:01:01"；86_399_999→"23:59:59"；86_400_000→"1天 00:00:00"；90_061_000→"1天 01:01:01"；负数→"00:00:00"。Swift 用 String(format: "%02d:%02d:%02d", Int32(h), Int32(m), Int32(s))，避免 64 位 Int 配 %d。 |
| `formatDurationShort` | fun formatDurationShort(millis: Long): String | 是 | 分钟分解与 formatDuration 相同（totalMinutes 先钳 0）。分支：days > 0 → "{days}天{hours}时"；否则 hours > 0 → "{hours}时{minutes}分"；否则 "{minutes}分"。全部无空格。示例：0→"0分"；59_999→"0分"；60_000→"1分"；3_600_000→"1时0分"；86_339_999→"23时59分"；86_400_000→"1天0时"；90_061_000→"1天1时"。注意 days>0 时丢弃分钟、但保留小时（可能为 0）。 |
| `SyncRange` | enum SyncRange(val label: String, val days: Long?)（Swift: enum SyncRange: String, CaseIterable） | 是 | TODAY("当天", 1)、THREE_DAYS("三天内", 3)、WEEK("一周内", 7)、MONTH("一个月内", 30)、ALL("全部导出", null)。days 是 Long?。rawValue 应保持 Kotlin 常量名 "TODAY"/"THREE_DAYS"/"WEEK"/"MONTH"/"ALL"，因为持久化与 fromLabelOrNull 都按这些名字匹配；label 只是中文显示文案。 |
| `SyncRange.windowFor` | fun windowFor(todayEpochDay: Long): Pair<Long, Long> | 是 | to = todayEpochDay；from = days != null ? (todayEpochDay - days + 1).coerceAtLeast(0) : 0L；返回 (from, to)，两端都是闭区间的 epochDay（不是毫秒、不做时区转换）。具体：TODAY→(today, today)；THREE_DAYS→(today-2, today)；WEEK→(today-6, today)；MONTH→(today-29, today)；ALL→(0, today)。若 today < days-1，from 被钳到 0 但 to 不变，窗口比名义天数短。 |
| `SyncRange.incrementalWindow` | fun incrementalWindow(todayEpochDay: Long, lastExportedAt: Long?): Pair<Long, Long> | 是 | lastExportedAt == null → 直接返回 windowFor(todayEpochDay)。否则 lastDay = Instant.ofEpochMilli(lastExportedAt).atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay()；返回 (min(lastDay, todayEpochDay), todayEpochDay)。注意：zone 硬编码为调用时刻的系统默认时区（不是参数）；from 不钳到 0；未来时间会被钳成 (today, today)。 |
| `SyncRange.fromLabelOrNull` | fun fromLabelOrNull(label: String?): SyncRange? | 是 | entries.firstOrNull { it.name == label }。匹配的是枚举常量名（"TODAY" 等），不是中文 label；null 或未知返回 null。生产代码 AppPrefs 里另有一份等价逻辑（firstOrNull + 默认 TODAY），Swift 可只保留一处。 |
| `isSingleDay` | fun Pair<Long, Long>.isSingleDay(todayEpochDay: Long): Boolean | 是 | first == second && second == todayEpochDay。用于 UI 判断窗口是否只有今天，从而只显示一个日期。 |
| `formatEpochDay` | fun formatEpochDay(epochDay: Long): String | 是 | LocalDate.ofEpochDay(epochDay).format(DateTimeFormatter.ofPattern("M月d日", Locale.CHINA))。无 zone 参数、无年份、月和日不补零。示例：epochDay 20762 → "11月5日"。epochDay 0 = 1970-01-01，按 proleptic Gregorian 计算，与时区无关。 |
| `generateFiveDigitNumber` | fun generateFiveDigitNumber(random: java.util.Random): String | 是 | 返回 (random.nextInt(90_000) + 10_000).toString()。均匀分布于 10000..99999（含端点），永远是 5 位 ASCII 数字，无前导 0。java.util.Random.nextInt(bound) 内部做拒绝采样以避免取模偏差。仓库层对同一 (personId, epochDay) 最多重试 20 次以避开当天已被占用的号码，并有全局唯一约束。 |
| `EventColors.SLATE` | const val SLATE: Long = 0xFF6B7280L | 是 | ARGB，alpha = FF，十进制 4285231744。仅用于未知事件类型兜底，不在可选色板 palette 内。Swift 用 UInt32(0xFF6B7280)。 |
| `EventColors.palette` | val palette: List<Long> | 是 | 顺序固定：BLUE 0xFF2A78D6、ORANGE 0xFFEB6834、GREEN 0xFF2E9E5B、PURPLE 0xFF8B5CF6、RED 0xFFD64545、TEAL 0xFF0E9F9A、PINK 0xFFEC4899、GOLD 0xFFC89B1E。统计本身不依赖该顺序，但新增自定义类型的默认选色依赖它。 |
| `DefaultEventTypes` | object（内置类型） | 是 | locked：typeKey "locked"、name "锁射"、color BLUE 0xFF2A78D6、sortOrder 0、builtIn true；unlocked：typeKey "unlocked"、name "开锁射精"、color ORANGE 0xFFEB6834、sortOrder 1、builtIn true。内置类型可改名改色但不可删除。 |

### 排序规则

数组/桶一律按时间升序：wearBuckets[0] 是本期第一天（月模式）或 1 月（年模式），bucketLabels 同序。eventStats 的顺序 = 已声明类型按 (sortOrder ASC, id ASC) 稳定排序，然后未知类型按 Kotlin String 自然序（UTF-16 code unit 字典序）升序追加在末尾；追加后不得重新排序。segmentDetails 按 segment.startAt 降序（稳定排序，相等时保持输入顺序）；eventDetails 按 occurredAt 降序（稳定排序）。全流程不使用任何本地化排序规则。SyncRange 枚举顺序为 TODAY、THREE_DAYS、WEEK、MONTH、ALL。

### 边界条件与陷阱

- Calendar.current 可能是非公历（佛历/和历/民国历），而 java.time 恒为 ISO-8601。Swift 必须显式使用 Calendar(identifier: .gregorian)；不要用 .iso8601（ISO 周历年在 1 月 1 日附近会差一年），也不要依赖 Calendar.current。
- Date 是时刻，LocalDate 是无时区的日期。epochDay 必须通过固定 UTC 的 Gregorian Calendar 换算：epochDay → Date(timeIntervalSince1970: epochDay * 86400)，再用 UTC 历读年月日；Date → epochDay 先用本地历取 Y/M/D，再用 UTC 历造 Date 并除以 86400（负数要 floor，Swift 的 / 向零截断）。
- LocalDate.now().toEpochDay() 用的是系统默认时区。若用 UTC 历算「今天」，在 Asia/Shanghai（UTC+8）本地 00:00–08:00 之间会得到前一天，导致窗口与随机数日期整体错一天。
- SyncPackage 里的 formatDay(epochDay * 86_400_000L, zone) 是「把 epochDay 当 UTC 午夜再按本地时区显示」，在西半球时区会显示成前一天；它不等价于 formatEpochDay。不要把该写法移植进 Swift 的日期格式化。
- Kotlin 的 sortedWith/sortedBy 是稳定排序，Swift 的 sorted 不保证稳定。eventStats（sortOrder 相同）、segmentDetails（startAt 相同）、eventDetails（occurredAt 相同）都必须加 enumerated 下标作为第二排序键，或使用稳定排序实现。
- Kotlin String 的自然序是 UTF-16 code unit 逐位比较；Swift String 的 < 使用 Unicode 规范等价排序（NFC 等价的字符串相等，emoji/CJK/组合字符的顺序可能不同）。未知类型 key 排序必须比较 Array(key.utf16) 才能逐字节一致。
- Int 宽度不同：Kotlin Int 是 32 位，Swift Int 是 64 位。sortOrder 的 Int.MAX_VALUE 必须写成 2147483647（或 Int32.max），不能写成 Int.max；若写入 SQLite/JSON 也必须保持 32 位范围。
- atStartOfDay(zone) 在 DST 缺口（当地 00:00 跳到 01:00）会返回当天第一个有效时刻（01:00）。Foundation 的 calendar.date(from: DateComponents(y,m,d)) 在缺口可能返回 nil 或被平移；稳妥做法是先取当天正午的 Date，再 calendar.startOfDay(for:)。绝不要用 atTime(MIDNIGHT) 的等价写法。
- 桶边界是本地午夜，所以 DST 切换日的桶宽是 23 或 25 小时；totalWearMillis 是墙钟毫秒差，不是「日历天数 × 86400000」。
- 全流程是半开区间：事件 [periodStart, periodEnd)、桶 [b[i], b[i+1])、佩戴重叠要求严格 overlapEnd > overlapStart。恰好落在边界上的数据是月/年统计最常见的 off-by-one 来源。
- crossesPeriodBoundary 用的是未裁剪的 segmentStart，但 segmentEnd 是钳制后的值（活动分段取 now），且用严格不等号 < / >：起点恰为 periodStart、终点恰为 periodEnd 都为 false。
- 活动分段（endAt == null）以 now 为终点。now 必须在每次 computeStats 调用时只取一次；若系统时钟回拨使 now < startAt，segmentEnd 会被抬到 startAt，得到零长度、不产生明细。
- 未知类型是「排序后追加」，id 默认为 0、sortOrder 为 Int.MAX_VALUE。若改为对最终列表重新排序，当某个已知类型 sortOrder 也等于 Int.MAX_VALUE 时顺序会与 Kotlin 不同。
- bucketIndex 返回 -1 是防御分支：此时 countsByType 仍然加一，但对应桶不加。Swift 必须保留这个不对称，否则总数与桶和会对不上。
- 已声明但本期零事件的类型仍会出现在 eventStats 中，buckets 是全 0 的长度 bucketCount 数组；UI 若过滤空行是 UI 的事，领域层不能过滤。
- eventTypes 中若存在重复 typeKey，会输出多行 EventTypeStat，共享同一份 count/buckets；Swift 不要用 Dictionary 去重。
- incrementalWindow 把 lastExportedAt 毫秒转成本地日期时用的是调用时刻的 TimeZone.current。用户在两次发送之间改时区或跨 DST，起点日期可能偏移一天。
- 两种钳制不要统一：windowFor 把 from 钳到 0（不钳 to）；incrementalWindow 把 from 钳到 today（不钳到 0）。
- formatEpochDay 没有 zone 参数，必须按 proleptic Gregorian 的 UTC 日期格式化；用本地日历会整体偏移。
- java.util.Random 的种子序列是特定 LCG 算法，Swift 的 SystemRandomNumberGenerator 无法复现 Random(42) 的输出。只有「10000...99999 均匀、5 位、无前导 0」这一契约可移植；若 fixture 依赖具体数值，需要自行实现该 LCG。
- 三种时长格式的空格规则不同且不可归一化：formatDuration 是「1 天 1 小时 1 分」（全空格），formatDurationShort 是「1天1时」（无空格），formatDurationWithSeconds 是「1天 01:01:01」（天与时间之间一个空格）。
- Swift 的 DateFormatter 必须设 locale = en_US_POSIX、calendar = gregorian，并给 CJK 字面量加引号（yyyy'年'M'月'd'日'）；否则用户的 12 小时制偏好或非拉丁数字系统会改变输出。
- yyyy 在 Java 与 ICU 中都是 year-of-era，1..9999 年一致；Foundation 的公历没有 0 年（1 BCE），Java 的 YearMonth 支持 year 0。真实数据不会触发，但严格 fixture 对齐时需注意。
- YEAR 模式的桶与 anchor.month 无关（始终 1..12 月，结束于次年 1 月 1 日）；MONTH 模式的标签是「日」的十进制数（不补零），长度随月份 28/29/30/31 变化。
- eventDetails 只包含本期事件，segmentDetails 只包含有正重叠的分段；不要误把全部历史返回给 UI。
- 颜色是 32 位无符号 ARGB 但用 Kotlin 有符号 Long 存储。Swift 用 UInt32 保存，alpha 恒为 FF；转 Color 时按 a/r/g/b 拆分，不要当成 0xRRGGBB 丢掉 alpha 位。

### 来源

- `c:/Users/yxsdc/Desktop/locker/app/src/main/java/com/yxsdc/locker/domain/Stats.kt:10`
- `c:/Users/yxsdc/Desktop/locker/app/src/main/java/com/yxsdc/locker/domain/Stats.kt:12-31`
- `c:/Users/yxsdc/Desktop/locker/app/src/main/java/com/yxsdc/locker/domain/Stats.kt:33-61`
- `c:/Users/yxsdc/Desktop/locker/app/src/main/java/com/yxsdc/locker/domain/Stats.kt:63-66`
- `c:/Users/yxsdc/Desktop/locker/app/src/main/java/com/yxsdc/locker/domain/Stats.kt:68-160`
- `c:/Users/yxsdc/Desktop/locker/app/src/main/java/com/yxsdc/locker/domain/Stats.kt:86-111`
- `c:/Users/yxsdc/Desktop/locker/app/src/main/java/com/yxsdc/locker/domain/Stats.kt:113-123`
- `c:/Users/yxsdc/Desktop/locker/app/src/main/java/com/yxsdc/locker/domain/Stats.kt:125-150`
- `c:/Users/yxsdc/Desktop/locker/app/src/main/java/com/yxsdc/locker/domain/Stats.kt:152-159`
- `c:/Users/yxsdc/Desktop/locker/app/src/main/java/com/yxsdc/locker/domain/Stats.kt:162-169`
- `c:/Users/yxsdc/Desktop/locker/app/src/main/java/com/yxsdc/locker/domain/TimeFormat.kt:9-16`
- `c:/Users/yxsdc/Desktop/locker/app/src/main/java/com/yxsdc/locker/domain/TimeFormat.kt:18-30`
- `c:/Users/yxsdc/Desktop/locker/app/src/main/java/com/yxsdc/locker/domain/TimeFormat.kt:32-64`
- `c:/Users/yxsdc/Desktop/locker/app/src/main/java/com/yxsdc/locker/domain/SyncRange.kt:10-16`
- `c:/Users/yxsdc/Desktop/locker/app/src/main/java/com/yxsdc/locker/domain/SyncRange.kt:22-26`
- `c:/Users/yxsdc/Desktop/locker/app/src/main/java/com/yxsdc/locker/domain/SyncRange.kt:32-39`
- `c:/Users/yxsdc/Desktop/locker/app/src/main/java/com/yxsdc/locker/domain/SyncRange.kt:41-44`
- `c:/Users/yxsdc/Desktop/locker/app/src/main/java/com/yxsdc/locker/domain/SyncRange.kt:47-55`
- `c:/Users/yxsdc/Desktop/locker/app/src/main/java/com/yxsdc/locker/domain/RandomNumber.kt:5-9`
- `c:/Users/yxsdc/Desktop/locker/app/src/main/java/com/yxsdc/locker/data/WearSegment.kt:11-19`
- `c:/Users/yxsdc/Desktop/locker/app/src/main/java/com/yxsdc/locker/data/EventRecord.kt:11-17`
- `c:/Users/yxsdc/Desktop/locker/app/src/main/java/com/yxsdc/locker/data/EventTypeEntity.kt:14-21`
- `c:/Users/yxsdc/Desktop/locker/app/src/main/java/com/yxsdc/locker/data/EventColors.kt:9-30`
- `c:/Users/yxsdc/Desktop/locker/app/src/main/java/com/yxsdc/locker/data/DefaultEventTypes.kt:4-30`
- `c:/Users/yxsdc/Desktop/locker/app/src/test/java/com/yxsdc/locker/domain/StatsTest.kt:43-198`
- `c:/Users/yxsdc/Desktop/locker/app/src/test/java/com/yxsdc/locker/domain/SyncRangeTest.kt:18-50`
- `c:/Users/yxsdc/Desktop/locker/app/src/test/java/com/yxsdc/locker/domain/RandomNumberTest.kt:9-17`
- `c:/Users/yxsdc/Desktop/locker/app/src/main/java/com/yxsdc/locker/ui/StatsScreen.kt:167-203`
- `c:/Users/yxsdc/Desktop/locker/app/src/main/java/com/yxsdc/locker/ui/LockerViewModel.kt:104-121`
- `c:/Users/yxsdc/Desktop/locker/app/src/main/java/com/yxsdc/locker/ui/LockerViewModel.kt:463-479`
- `c:/Users/yxsdc/Desktop/locker/app/src/main/java/com/yxsdc/locker/data/LockerRepository.kt:57-80`
- `c:/Users/yxsdc/Desktop/locker/app/src/main/java/com/yxsdc/locker/data/LockerRepository.kt:681-695`
- `c:/Users/yxsdc/Desktop/locker/app/src/main/java/com/yxsdc/locker/data/SyncPackage.kt:255-269`


## 同步包

**格式**：Locker 同步包（locker-sync v1）：外层 ZIP（manifest.json 明文 + payload.bin 加密）包裹内层 ZIP（data.json + photos/&lt;epochDay&gt;.jpg），payload.bin = 12 字节 IV + AES-256-GCM(内层 zip) + 16 字节 tag


### 字段

| 路径 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `outerZip` | ZIP 文件（application/zip，单层，无目录条目） | 是 | 顶层容器。只允许两个条目：第 1 个 manifest.json，第 2 个 payload.bin。条目名必须精确匹配（区分大小写、无前导 ./）。读取方用流式 ZipInputStream 顺序扫描，最多 64 个条目（MAX_OUTER_ENTRIES）；其它条目会被忽略但计入 64 上限。整包文件上限 300MB（MAX_PACKAGE_BYTES，由调用方在落盘时强制）。文件名约定 locker-yyyyMMdd-HHmm.zip（不在格式内，仅约定）。 |
| `outerZip.manifest.json` | ZIP 条目，方法 DEFLATED（默认压缩级别 6），本地头置 bit3 + data descriptor | 是 | UTF-8 明文 JSON，内容为 manifest（见下）。Java 端因未预置 size/crc，ZipOutputStream 自动写 data descriptor；体积上限 1MB（MAX_MANIFEST_BYTES），超限即报「同步数据超出体积上限」。读取时只取第一个同名条目，且必须出现在前 64 个条目内。 |
| `outerZip.payload.bin` | ZIP 条目，方法 DEFLATED + Deflater.NO_COMPRESSION（0 级，即 deflate stored block），本地头置 bit3 + data descriptor | 是 | 载荷已是密文，再压缩无收益；用 0 级 DEFLATED 的原因正是「允许流式写入未知大小」。解密后内容为内层 ZIP。注意：Android 读外层用的是 ZipInputStream（只看本地头），若 Swift 侧改成 STORED 且本地头未写 size/compressedSize，Java/Android 会抛 invalid entry size —— 所以 Swift 复现时要么沿用 DEFLATED（可带 descriptor），要么 STORED 但必须预写真实 size/compressedSize/crc32。加密字节总量上限 300MB（LimitedInputStream）。 |
| `payload.bin.iv` | bytes[12] | 是 | payload.bin 的前 12 字节为明文 IV（SyncCrypto.IV_BYTES = 12），由 SecureRandom 每次随机生成，不复用。不足 12 字节 → 「加密数据不完整」。 |
| `payload.bin.ciphertext` | bytes[N-28] | 是 | AES-256-GCM 密文，明文 = 内层 ZIP 的完整字节。无 AAD。密钥 = PBKDF2-HMAC-SHA256(normalizeCode(code), salt=UTF-8(codeSalt 十六进制字符串), 120000 次, 32 字节)。解密后字节上限 300MB。 |
| `payload.bin.tag` | bytes[16] | 是 | GCM 认证标签 128 位，追加在密文末尾（JCE 的 doFinal 行为）。因此 payload.bin 布局严格为 IV(12) \|\| ciphertext \|\| tag(16)，最短 28 字节。CryptoKit 的 AES.GCM.SealedBox.combined 恰好就是 nonce\|\|ciphertext\|\|tag，可直接映射。 |
| `manifest.formatVersion` | JSON number(Int) / Swift Int | 是 | 固定 1（SyncPackage.FORMAT_VERSION）。解码用 optInt("formatVersion", -1) 严格等于 1，否则抛「同步数据版本不受支持，请升级 App」。 |
| `manifest.kind` | JSON string / Swift String | 是 | 固定 "locker-sync"（SyncPackage.KIND），必须精确相等，否则「不是 Locker 同步数据」。 |
| `manifest.identity.id` | JSON string / Swift String | 是 | 身份 UUID（SyncIdentity.identityId），导入时用于查找本地绑定、判断是否重复导入（配合整包 SHA-256）。 |
| `manifest.identity.name` | JSON string / Swift String | 是 | 人物显示名（SyncIdentity.displayName），用于新建人物与同名冲突提示。 |
| `manifest.identity.salt` | JSON string / Swift String | 是 | 16 字节随机盐的十六进制字符串（32 个小写 hex 字符，SyncCrypto.newSalt）。注意：PBKDF2 与 SHA-256 用的都是这个十六进制字符串的 UTF-8 字节，不是解码后的 16 字节。 |
| `manifest.identity.codeHash` | JSON string / Swift String | 是 | SHA-256(salt 的 UTF-8 字节 \|\| normalizeCode(code) 的 UTF-8 字节) 的小写十六进制（64 字符），无分隔符。zip 内绝不出现明文身份码。 |
| `manifest.exportedAt` | JSON number(Int64) / Swift Int64 | 是 | 导出时刻，Unix 毫秒（System.currentTimeMillis()）。仅展示用途，不参与校验。 |
| `manifest.window.fromEpochDay` | JSON number(Int64) / Swift Int64 | 是 | 窗口起始日，LocalDate.toEpochDay()（1970-01-01 起的天数），闭区间下界。导入方只比对这一天到 toEpochDay 之间的本地数据。 |
| `manifest.window.toEpochDay` | JSON number(Int64) / Swift Int64 | 是 | 窗口结束日，闭区间上界。 |
| `innerZip` | ZIP 文件（payload.bin 解密后的全部字节） | 是 | 条目数上限 MAX_PHOTOS + 16 = 1016。读取方用 ZipFile（走中央目录，不用 ZipInputStream），因此内层条目的 STORED + data descriptor 是可接受的。只识别 data.json 与 photos/<digits>.jpg，其它名字静默忽略但仍计数。 |
| `innerZip.data.json` | ZIP 条目，方法 DEFLATED（默认级别），UTF-8 明文 JSON | 是 | 业务数据，紧凑序列化（org.json toString()，无空白、无缩进）。体积上限 20MB（MAX_DATA_JSON_BYTES）。必须存在，否则「同步数据缺少内容」。重复出现时后者覆盖前者（不报错）。 |
| `innerZip.photos/<epochDay>.jpg` | ZIP 条目，方法 STORED（method=0），本地头预写 size / compressedSize / crc32 | 是 | 条目名精确为 photos/ + 十进制天数 + .jpg，如 photos/20355.jpg；不得补零（photos/020355.jpg 会解析成同一天并触发「照片条目重复」）。用 STORED 是因为 JPEG 已压缩。单张上限 10MB（MAX_PHOTO_BYTES），累计上限 300MB；同一 epochDay 重复条目 → 「照片条目重复」；名字匹配正则但数字溢出 Long → 「照片条目名称非法」。Java 侧用 storedEntry() 预置 size=compressedSize=bytes.size、crc=CRC32(bytes)，以避免 data descriptor（但内层读的是 ZipFile，descriptor 其实也能读）。 |
| `data.json.personName` | JSON string / Swift String | 是 | 人物名称（Person.name），导入时若需新建人物即用它，长度 1–20。 |
| `data.json.eventTypes[].typeKey` | JSON string / Swift String | 是 | 事件类型稳定键（内置为固定 key）。导入时若本地不存在该 typeKey 才插入。 |
| `data.json.eventTypes[].name` | JSON string / Swift String | 是 | 事件类型显示名，1–20 字符。 |
| `data.json.eventTypes[].colorArgb` | JSON number(Int64) / Swift Int64 | 是 | ARGB 颜色，含不透明 alpha，值可达 4278190080（0xFF000000）。Swift 侧务必用 Int64/Int，用 Int32 会溢出。注意 payload 里不含 id。 |
| `data.json.eventTypes[].sortOrder` | JSON number(Int) / Swift Int | 是 | 排序序号。 |
| `data.json.eventTypes[].builtIn` | JSON boolean / Swift Bool | 是 | 是否内置类型（内置类型不可删除）。 |
| `data.json.segments[].startAt` | JSON number(Int64) / Swift Int64 | 是 | 戴上时刻，Unix 毫秒。也是分段配对的自然键（全局唯一假定）。 |
| `data.json.segments[].endAt` | JSON number(Int64) | null / Swift Int64? | 是 | 取下时刻，Unix 毫秒；佩戴中时为 JSON null（键仍写出）。读取端用 isNull 判定，缺键也视作 null。不含 id / personId（打包时已置 0 且不序列化）。 |
| `data.json.events[].type` | JSON string / Swift String | 是 | 事件类型的 typeKey（不是显示名）。 |
| `data.json.events[].occurredAt` | JSON number(Int64) / Swift Int64 | 是 | 发生时刻，Unix 毫秒。与 type 组成配对键 "<type>#<occurredAt>"。 |
| `data.json.events[].note` | JSON string | null / Swift String? | 是 | 备注。空串或纯空白在编码时统一写成 JSON null（isNullOrBlank 判定）；读取端 null 与缺键都当 nil。 |
| `data.json.randomNumbers[].epochDay` | JSON number(Int64) / Swift Int64 | 是 | 日期天数，配对键。同一人物同一天只有一条。 |
| `data.json.randomNumbers[].number` | JSON string / Swift String | 是 | 5 位数字字符串（10000–99999），注意是字符串不是数字，Swift 侧不要解码成 Int。 |
| `data.json.randomNumbers[].createdAt` | JSON number(Int64) / Swift Int64 | 是 | 生成时刻，Unix 毫秒。参与写入但不参与差异判定。 |
| `data.json.photoDays[]` | JSON number(Int64) / Swift Int64 | 是 | 旧版本兼容字段：纯日期数组，升序。新版本仍必须写出（供旧读取端使用），但新读取端优先看 photoRefs。 |
| `data.json.photoRefs[].epochDay` | JSON number(Int64) / Swift Int64 | 是 | 新字段。与 photoDays 同序、一一对应。 |
| `data.json.photoRefs[].fingerprint` | JSON string / Swift String | 是 | 照片 JPEG 的 SHA-256 十六进制前 16 个字符（小写），DailyReport.photoFingerprint。用于不读照片字节就能比对内容是否被替换。空串表示旧包未携带指纹，读取方必须回填。 |

### 排序规则

外层 zip：manifest.json 先、payload.bin 后，无目录条目，均带 data descriptor（bit3）。内层 zip：data.json 第一，随后 photos 按 epochDay 升序 —— 注意 writePackage 本身不再排序，顺序取决于调用方传入的 photos 列表（collectSyncExport 用 ORDER BY epochDay ASC）。manifest JSON 键序为插入序（formatVersion, kind, identity{id,name,salt,codeHash}, exportedAt, window{fromEpochDay,toEpochDay}），toString(2) 两空格缩进。data.json 键序为插入序（personName, eventTypes, segments, events, randomNumbers, photoDays, photoRefs），紧凑无空白；数组分别按 segments.startAt 升序、events.occurredAt 升序、randomNumbers.epochDay 升序、photos.epochDay 升序，photoDays 与 photoRefs 同序对齐。读取端不得依赖键序。computeSyncChanges 输出顺序固定为：全部分段（startAt 数值升序）→ 全部事件（键 \"type#occurredAt\" 的字符串字典序升序）→ 全部照片（epochDay 数值升序）→ 全部随机数（epochDay 数值升序）；每一类内部按 (local.keys ∪ incoming.keys) 排序后逐键产出。

### 边界条件与陷阱

- 【ZIPFoundation 头号陷阱】外层 payload.bin 不能写成 STORED + 未知尺寸。Android 读外层走 ZipInputStream（只看本地头），对 method=STORED 且本地头 size/compressedSize 为 -1 的条目直接抛 invalid entry size，整个包会被判为「验证码不正确或数据已损坏」。要么沿用 DEFLATED（允许 bit3 + data descriptor），要么 STORED 但必须预写真实 size/compressedSize/crc32。内层没有这个限制（用 ZipFile 走中央目录）。
- 【ZIPFoundation 陷阱】没有公开的 per-entry deflate 压缩级别 API，无法复现「DEFLATED + 0 级」。这纯属性能/体积优化，不影响兼容性：用默认 deflate 写 payload.bin 完全可被 Android 读取（只是白费一点 CPU）。若要严格复现，只能自己写 deflate stored block。
- 【ZIPFoundation 陷阱】流式 addEntry(with:provider:) 必须预先给出 uncompressedSize，无法像 Java 那样单趟边压缩边写未知大小。所以 Swift 侧必须先把内层 zip 落到临时文件（或 Data），才能算出 payload.bin 的 uncompressedSize = 12 + innerZipSize + 16。
- 【ZIPFoundation 陷阱】addEntry 需要在 provider 上回退以计算 CRC32；不可 rewind 的流（例如直接接 GCM 加密输出）会失败或写出错误 CRC。照片用 Data 提供，payload.bin 用临时文件/Data 提供。
- 【ZIPFoundation 陷阱】不要用 addEntry(with:relativeTo:) 打包目录，否则会写入 photos/ 目录条目；虽然 Android 会静默忽略（不计入照片集合），但会占用 1016 条目上限，且与 Android 产物不一致。
- 【CryptoKit 陷阱】AES.GCM 是一次性 API，300MB 内层 zip 一次性 seal 会造成数百 MB 峰值内存，iOS 极易被 jetsam。要么用增量 GCM（CommonCrypto 的 CCCryptorGCM*，已废弃但可用；或 CryptoSwift/OpenSSL），要么分块写入临时文件。解密同理。
- 【CryptoKit 陷阱】SealedBox.combined 的布局正好是 nonce(12)||ciphertext||tag(16)，与 JCE 的 IV||ciphertext||tag 完全一致，可直接对应；但若手写 nonce/ciphertext/tag 三段，必须自己按此顺序拼接，不能把 tag 放前面。Nonce 必须 12 字节，无 AAD。
- 【CryptoKit 陷阱】认证失败（tag 不匹配）会抛 CryptoKitError.authenticationFailure，必须映射成与 Android 相同的用户提示「验证码不正确或数据已损坏」，不能把底层错误抛给 UI。
- 【KDF】PBKDF2-HMAC-SHA256，password = normalizeCode(code)（转大写后只保留字母/数字，连字符与空白剔除，即 "ABCD-EFGH" → "ABCDEFGH"），salt = codeSalt 十六进制字符串的 UTF-8 字节（不是解码后的 16 字节），迭代 120000，输出 32 字节。codeHash = SHA-256(salt 的 UTF-8 字节 || normalizeCode 后的 UTF-8 字节) 的小写 hex。注意 Kotlin 的 isLetterOrDigit 是 Unicode 语义、uppercase() 用 Locale.ROOT；Swift 用 uppercased() + unicodeScalars 过滤字母/数字可对齐，别用受 locale 影响的比较。
- 【photoRefs 与 photoDays 的优先级】decodePayloadJson 是「全有或全无」：photoRefs 存在（哪怕是空数组）就完全忽略 photoDays；只有 photoRefs 缺失时才退回 photoDays（指纹置空串）。绝不能把两者合并。若包里 photoRefs=[] 而 photoDays 非空，photos 解析为空，随后「条目数 == 照片数」校验会因实际存在照片条目而报「同步数据包含多余条目」。
- 【旧版本包兼容路径 1：无 photoRefs】解析出的 SyncPhotoRef.fingerprint 为空串，parseInnerZip 会逐张打开 photos/<day>.jpg 计算 SHA-256 前 16 位 hex 回填，然后才返回；因此 computeSyncChanges 拿到的 incoming 指纹总是非空。Swift 复现时必须保留这条回填逻辑，否则旧包会与本地照片全部误判为 MODIFY（"" != "abc..."）。
- 【旧版本包兼容路径 2：data.json 缺字段】endAt / note 键缺失时按 null 处理（org.json 的 isNull 对缺键返回 true）。但 personName、eventTypes、segments、events、randomNumbers 五个字段是必需的，缺失会抛 IllegalArgumentException「同步数据内容不完整」。photoDays 与 photoRefs 都可缺失，两者都缺 → photos 为空。
- 【旧版本包兼容路径 3：清单 BOM/空白】decodeManifest 先 trim() 再去掉开头的 U+FEFF，所以带 BOM 的 manifest.json 可读。Swift 侧应在 JSON 解析前做同样的 trim + BOM 剥离，否则 JSONDecoder 直接报错。
- 【旧版本包兼容路径 4：payload.bin 被正常压缩】旧包可能是默认级别 DEFLATED，而不是 0 级。解密路径完全相同，只是进度估算依赖 entry.size（>0 时 total = size - 12；0 或未知时退化为不确定进度）。不要假设 0 级。
- 【旧版本包兼容路径 5：manifest.json 位置】readManifest 顺序扫描，返回第一个名为 manifest.json 的条目，不要求它是第一个条目；但必须落在前 64 个条目内。payload.bin 同理只取第一个，重复条目被忽略。
- 【照片天数重复】data.json 里同一 epochDay 出现两次会导致 photoEntries.size(去重) != payload.photos.size(未去重)，报「同步数据包含多余条目」。zip 内同一 photos/<day>.jpg 出现两次则报「照片条目重复」。Swift 侧两种都要显式拒绝。
- 【白名单】内层只消费 data.json 与精确匹配 ^photos/([0-9]+)\.jpg$ 的条目；其余名字静默忽略但仍计入 1016 条目上限。外层只消费 manifest.json 与 payload.bin；其余忽略但计入 64 条目上限。照片名里的数字用 toLongOrNull 语义（溢出返回 nil → 「照片条目名称非法」），Swift 用 Int64(String) 保持一致。
- 【三重照片一致性校验】① parseInnerZip：photoEntries.keys ⊇ payload 声明日期，且 photoEntries.size == payload.photos.size；② verifyPhotoEntries：重新打开 zip，用正则收集实际日期集合，要求与 payload 日期集合严格相等；③ applySyncImport 时按天读取，读不到就跳过该条。三者缺一都会被恶意包绕过。
- 【diff 配对与判定】分段按 startAt（数值），事件按 "<type>#<occurredAt>"（字符串），照片按 epochDay，随机数按 epochDay；取两侧键的并集后排序遍历。判定：分段仅比较 endAt（null 与非 null 视为不同）；事件仅比较归一化后的 note（isNullOrBlank → nil，即空白等于空）；照片仅比较 fingerprint 字符串；随机数仅比较 number（createdAt 忽略）。不比较 id / personId / updatedAt。事件键的排序是 UTF-16 字典序，"a#10" 会排在 "a#9" 前面 —— Swift 若用数值比较会得到不同顺序（只影响列表顺序，不影响结果集）。
- 【默认勾选】defaultSelectedSyncKeys = 所有 action != REMOVE 的 key（ADD + MODIFY 默认勾选，删除必须用户主动勾选）。
- 【展示文案的时区怪癖】照片/随机数的标题用 epochDay * 86_400_000 当成毫秒再按系统时区格式化，等价于把 epochDay 当作 UTC 午夜；在 UTC+8 仍显示正确日期，但在负时区（如 UTC-5）会显示成前一天。若要与 Android 逐字一致，就照抄这个换算；若要修正，需接受文案不同。分段/事件标题用真实毫秒 + 系统时区，格式 "MM-dd" / "HH:mm" / "MM-dd HH:mm"，locale zh_CN，endAt 为 null 显示「佩戴中」。
- 【体积上限】MAX_PACKAGE_BYTES=300MB 同时约束三处：落盘的整包、payload.bin 的压缩字节（LimitedInputStream）、解密后的内层 zip 字节；MAX_MANIFEST_BYTES=1MB；MAX_DATA_JSON_BYTES=20MB；MAX_PHOTO_BYTES=10MB（单张，读取与写入都查）；MAX_PHOTOS=1000；MAX_OUTER_ENTRIES=64；内层条目 1016；照片总量 300MB。另有调用方预检 MAX_EXPORT_PHOTO_BYTES=260MB（留出加密/zip 包装余量），不属于归档层校验。
- 【写包失败清理】writePackage 在任何异常时 delete 目标文件；parseEncryptedPayload 在失败时 delete 内层临时 zip，并把非 IllegalArgumentException 一律包装成「验证码不正确或数据已损坏」。Swift 侧临时文件生命周期（incoming.tmp.zip / inner.tmp.zip）也应在 cancel/apply 后清理。
- 【不是格式但会影响联调】导入去重靠整包 SHA-256（与上次导入同一 identityId 的哈希比较），Swift 产出的字节必然与 Android 不同，因此同一份数据在两端来回导入时去重会失效；这属于预期行为，不要试图逐字节复现 org.json 的序列化。
- 【JSON 解析严格度】Android 的 org.json 是宽松解析器（允许单引号、未加引号的键、尾逗号），Swift 的 JSONDecoder 是严格 RFC 8259。Android 自己写出的包一定合法，所以正常路径无碍；若要读第三方/损坏文件，需要宽松解析器或先做校验。
- 【读包顺序】Swift 侧不要先解析中央目录再解密：正确顺序是 ① 整包落盘并算 SHA-256（≤300MB）→ ② 读 manifest 做身份/版本校验与是否需验证码的判断 → ③ 用 PBKDF2 派生密钥 → ④ 取 payload.bin 前 12 字节 IV → ⑤ GCM 解密到内层 zip 临时文件 → ⑥ 解析内层并回填旧包指纹 → ⑦ 三重照片校验 → ⑧ computeSyncChanges。任何一步失败都要给出与 Android 一致的中文提示。
- 【ZIPFoundation 读取端】Archive(url:accessMode:.read) 走中央目录，能正确读出带 data descriptor 的 Java 条目；但 Archive(data:) 会把整包读进内存，300MB 场景应坚持用文件 URL。extract 默认校验 CRC32（比 Android 的 ZipInputStream 更严格），失败要映射成统一的损坏提示。

### 来源

- `app/src/main/java/com/yxsdc/locker/data/SyncArchive.kt:13-26`
- `app/src/main/java/com/yxsdc/locker/data/SyncArchive.kt:28-37`
- `app/src/main/java/com/yxsdc/locker/data/SyncArchive.kt:45-103`
- `app/src/main/java/com/yxsdc/locker/data/SyncArchive.kt:63-70`
- `app/src/main/java/com/yxsdc/locker/data/SyncArchive.kt:69-72`
- `app/src/main/java/com/yxsdc/locker/data/SyncArchive.kt:72-96`
- `app/src/main/java/com/yxsdc/locker/data/SyncArchive.kt:77-95`
- `app/src/main/java/com/yxsdc/locker/data/SyncArchive.kt:105-121`
- `app/src/main/java/com/yxsdc/locker/data/SyncArchive.kt:134-151`
- `app/src/main/java/com/yxsdc/locker/data/SyncArchive.kt:154-177`
- `app/src/main/java/com/yxsdc/locker/data/SyncArchive.kt:182-191`
- `app/src/main/java/com/yxsdc/locker/data/SyncArchive.kt:193-245`
- `app/src/main/java/com/yxsdc/locker/data/SyncArchive.kt:206-216`
- `app/src/main/java/com/yxsdc/locker/data/SyncArchive.kt:219-232`
- `app/src/main/java/com/yxsdc/locker/data/SyncArchive.kt:247-302`
- `app/src/main/java/com/yxsdc/locker/data/SyncArchive.kt:258-272`
- `app/src/main/java/com/yxsdc/locker/data/SyncArchive.kt:275-300`
- `app/src/main/java/com/yxsdc/locker/data/SyncArchive.kt:343-348`
- `app/src/main/java/com/yxsdc/locker/data/SyncPackage.kt:16-17`
- `app/src/main/java/com/yxsdc/locker/data/SyncPackage.kt:19-43`
- `app/src/main/java/com/yxsdc/locker/data/SyncPackage.kt:45-65`
- `app/src/main/java/com/yxsdc/locker/data/SyncPackage.kt:67-88`
- `app/src/main/java/com/yxsdc/locker/data/SyncPackage.kt:91-161`
- `app/src/main/java/com/yxsdc/locker/data/SyncPackage.kt:144-158`
- `app/src/main/java/com/yxsdc/locker/data/SyncPackage.kt:163-231`
- `app/src/main/java/com/yxsdc/locker/data/SyncPackage.kt:211-226`
- `app/src/main/java/com/yxsdc/locker/data/SyncPackage.kt:275-323`
- `app/src/main/java/com/yxsdc/locker/data/SyncPackage.kt:325-364`
- `app/src/main/java/com/yxsdc/locker/data/SyncPackage.kt:366-402`
- `app/src/main/java/com/yxsdc/locker/data/SyncPackage.kt:404-439`
- `app/src/main/java/com/yxsdc/locker/data/SyncPackage.kt:444-446`
- `app/src/main/java/com/yxsdc/locker/data/SyncCrypto.kt:18-24`
- `app/src/main/java/com/yxsdc/locker/data/SyncCrypto.kt:34-35`
- `app/src/main/java/com/yxsdc/locker/data/SyncCrypto.kt:44-49`
- `app/src/main/java/com/yxsdc/locker/data/SyncCrypto.kt:54-64`
- `app/src/main/java/com/yxsdc/locker/data/SyncCrypto.kt:75-88`
- `app/src/main/java/com/yxsdc/locker/data/DailyReport.kt:70-75`
- `app/src/main/java/com/yxsdc/locker/data/EventTypeEntity.kt:14-21`
- `app/src/main/java/com/yxsdc/locker/data/DailyRandomNumber.kt:10-20`
- `app/src/main/java/com/yxsdc/locker/data/LockerRepository.kt:387-412`
- `app/src/main/java/com/yxsdc/locker/data/LockerRepository.kt:439-573`
- `app/src/main/java/com/yxsdc/locker/ui/LockerViewModel.kt:486-578`
- `app/src/main/java/com/yxsdc/locker/ui/LockerViewModel.kt:596-600`
- `app/src/main/java/com/yxsdc/locker/ui/LockerViewModel.kt:625-682`
- `app/src/main/java/com/yxsdc/locker/ui/LockerViewModel.kt:684-757`
- `app/src/main/java/com/yxsdc/locker/ui/LockerViewModel.kt:759-799`
