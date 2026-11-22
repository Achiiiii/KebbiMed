package tw.kebbi.medreminder.data

/**
 * 藥品資料庫：台灣 55 歲以上長者常見用藥 41 種。
 * 與 Sheets「凱比用藥資料庫」、Figma、後台一致。
 * 時段為「常見服用時段」，實際以醫囑為準；照服員可在後台調整。
 */
object MedDb {
    val all: List<Med> = listOf(
        Med("M01", "降血壓藥", listOf(Slot.POST_B), "1 顆", "Amlodipine", "高血壓", "Norvasc 脈優"),
        Med("M02", "降血壓藥", listOf(Slot.POST_B), "1 顆", "Losartan", "高血壓", "Cozaar 可悅您"),
        Med("M03", "降血壓藥", listOf(Slot.POST_B), "1 顆", "Valsartan", "高血壓", "Diovan 得安穩"),
        Med("M04", "降血壓藥", listOf(Slot.POST_B), "1 顆", "Nifedipine", "高血壓", "Adalat OROS 冠達悅"),
        Med("M05", "降血脂藥", listOf(Slot.BED), "1 顆", "Atorvastatin", "高血脂", "Lipitor 立普妥"),
        Med("M06", "降血脂藥", listOf(Slot.BED), "1 顆", "Rosuvastatin", "高血脂", "Crestor 冠脂妥"),
        Med("M07", "降血糖藥", listOf(Slot.POST_B, Slot.POST_D), "1 顆", "Metformin", "糖尿病", "Glucophage 庫魯化"),
        Med("M08", "降血糖藥", listOf(Slot.PRE_B), "1 顆", "Glimepiride", "糖尿病", "Amaryl 瑪爾胰"),
        Med("M09", "降血糖藥", listOf(Slot.POST_B), "1 顆", "Sitagliptin", "糖尿病", "Januvia 佳糖維"),
        Med("M10", "降血糖藥", listOf(Slot.POST_B), "1 顆", "Linagliptin", "糖尿病", "Trajenta 糖漸平"),
        Med("M11", "心腎保護藥", listOf(Slot.POST_B), "1 顆", "Empagliflozin", "心衰竭", "Jardiance 恩排糖"),
        Med("M12", "心腎保護藥", listOf(Slot.POST_B), "1 顆", "Dapagliflozin", "心衰竭", "Forxiga 福適佳"),
        Med("M13", "利尿劑", listOf(Slot.POST_B, Slot.POST_D), "1 顆", "Furosemide", "心衰竭", "Lasix 來適泄"),
        Med("M14", "利尿劑", listOf(Slot.POST_B), "1 顆", "Spironolactone", "心衰竭", "Aldactone 安達通"),
        Med("M15", "心臟藥", listOf(Slot.POST_B, Slot.POST_D), "1 顆", "Sacubitril/Valsartan", "心衰竭", "Entresto 健安心"),
        Med("M16", "心臟藥", listOf(Slot.POST_B), "1 顆", "Bisoprolol", "心衰竭", "Concor 康肯"),
        Med("M17", "心臟藥", listOf(Slot.POST_B, Slot.POST_D), "1 顆", "Carvedilol", "心衰竭", "Dilatrend 達利全"),
        Med("M18", "強心藥", listOf(Slot.BED), "半顆", "Digoxin", "心衰竭", "Lanoxin 隆我心"),
        Med("M19", "阿斯匹靈", listOf(Slot.POST_B), "1 顆", "Aspirin", "抗血栓", "Aspirin 100mg"),
        Med("M20", "抗血栓藥", listOf(Slot.POST_B), "1 顆", "Clopidogrel", "抗血栓", "Plavix 保栓通"),
        Med("M21", "抗凝血藥", listOf(Slot.POST_D), "1 顆", "Rivaroxaban", "抗血栓", "Xarelto 拜瑞妥"),
        Med("M22", "抗凝血藥", listOf(Slot.POST_B, Slot.POST_D), "1 顆", "Apixaban", "抗血栓", "Eliquis 艾必克凝"),
        Med("M23", "抗凝血藥", listOf(Slot.POST_B), "1 顆", "Edoxaban", "抗血栓", "Lixiana 里先安"),
        Med("M24", "抗凝血藥", listOf(Slot.POST_D), "1 顆", "Warfarin", "抗血栓", "Coumadin 等"),
        Med("M25", "舌下含片", listOf(), "1 顆", "Nitroglycerin", "心絞痛", "NTG 舌下錠"),
        Med("M26", "軟便藥", listOf(Slot.BED), "1 顆", "Sennoside", "腸胃", "各廠牌"),
        Med("M27", "軟便藥", listOf(Slot.BED), "1 顆", "Magnesium oxide", "腸胃", "各廠牌"),
        Med("M28", "胃藥", listOf(Slot.PRE_B), "1 顆", "Esomeprazole", "腸胃", "Nexium 耐適恩"),
        Med("M29", "胃藥", listOf(Slot.PRE_B), "1 顆", "Pantoprazole", "腸胃", "Pantoloc 保衛康治潰樂"),
        Med("M30", "脹氣藥", listOf(Slot.POST_B, Slot.POST_L, Slot.POST_D), "1 顆", "Dimethicone", "腸胃", "Gascon 加斯克兒"),
        Med("M31", "腸胃蠕動藥", listOf(Slot.PRE_B, Slot.PRE_L, Slot.PRE_D), "1 顆", "Mosapride", "腸胃", "Gasmotin 加斯清"),
        Med("M32", "痛風藥", listOf(Slot.POST_B), "1 顆", "Allopurinol", "痛風", "各廠牌"),
        Med("M33", "痛風藥", listOf(Slot.POST_B), "1 顆", "Febuxostat", "痛風", "Feburic 福避痛"),
        Med("M34", "痛風藥", listOf(Slot.POST_B), "1 顆", "Colchicine", "痛風", "各廠牌"),
        Med("M35", "攝護腺藥", listOf(Slot.BED), "1 顆", "Tamsulosin", "泌尿", "Harnalidge 活路利淨"),
        Med("M36", "安眠藥", listOf(Slot.BED), "1 顆", "Zolpidem", "睡眠", "Stilnox 使蒂諾斯"),
        Med("M37", "止痛藥", listOf(), "1 顆", "Acetaminophen", "止痛", "Panadol 普拿疼"),
        Med("M38", "消炎止痛藥", listOf(Slot.POST_B), "1 顆", "Celecoxib", "止痛", "Celebrex 希樂葆"),
        Med("M39", "骨鬆藥", listOf(Slot.WAKE), "1 顆", "Alendronate", "骨質疏鬆", "Fosamax 福善美", weeklyOn = 2),
        Med("M40", "鈣片", listOf(Slot.POST_D), "1 顆", "Calcium + Vit D", "骨質疏鬆", "各廠牌"),
        Med("M41", "甲狀腺藥", listOf(Slot.WAKE), "1 顆", "Levothyroxine", "甲狀腺", "Eltroxin 昂特欣"),
    )

    val categories = listOf("心衰竭", "高血壓", "糖尿病", "高血脂", "抗血栓", "心絞痛", "腸胃", "痛風", "泌尿", "睡眠", "止痛", "骨質疏鬆", "甲狀腺")

    private val byId = all.associateBy { it.id }
    fun get(id: String): Med? = byId[id]
    fun inCategory(cat: String) = all.filter { it.category == cat }

    /**
     * 解析藥袋 QR Code。接受：
     * 「KEBBI:M13」、「M13」、或內容含學名（例：Furosemide 40mg）。
     * 各醫院藥袋 QR 格式不同，正式上線前要依合作醫院的格式擴充這裡。
     */
    fun parseQr(text: String): Med? {
        val t = text.trim()
        Regex("(?i)\\bM(\\d{2})\\b").find(t)?.let { get("M" + it.groupValues[1])?.let { m -> return m } }
        val lower = t.lowercase()
        return all.firstOrNull { lower.contains(it.generic.lowercase()) }
    }
}
