package tw.kebbi.medreminder.sync

import tw.kebbi.medreminder.api.MealRelation
import tw.kebbi.medreminder.api.Slot
import tw.kebbi.medreminder.api.Slot.AFTER_BREAKFAST
import tw.kebbi.medreminder.api.Slot.AFTER_DINNER
import tw.kebbi.medreminder.api.Slot.AFTER_LUNCH
import tw.kebbi.medreminder.api.Slot.BEDTIME
import tw.kebbi.medreminder.api.Slot.BEFORE_BREAKFAST
import tw.kebbi.medreminder.api.Slot.BEFORE_DINNER
import tw.kebbi.medreminder.api.Slot.BEFORE_LUNCH
import tw.kebbi.medreminder.api.Slot.WAKE
import tw.kebbi.medreminder.api.Weekday
import tw.kebbi.medreminder.api.dto.CatalogEntryDto
import tw.kebbi.medreminder.api.dto.DoseDto

/**
 * 假後端的藥品目錄初始資料：原本 `data/MedDb.kt` 的 M01–M41（台灣 55 歲以上長者常見用藥）。
 * 時段是「常見服用時段」，實際以醫囑為準。正式版由後端維護（D6、第 6.3 節 E1）。
 * 別名 = 學名 + 商品名拆開的英文、中文（「各廠牌」不算）。比對規則見 `scan/CatalogMatcher`。
 */
object FakeCatalog {
    val categories = listOf("心衰竭", "高血壓", "糖尿病", "高血脂", "抗血栓", "心絞痛", "腸胃", "痛風", "泌尿", "睡眠", "止痛", "骨質疏鬆", "甲狀腺")

    private fun med(id: String, name: String, slots: List<Slot>, dose: String, generic: String, cat: String, brand: String, weekly: Weekday? = null): CatalogEntryDto {
        val brandTokens = if (brand == "各廠牌") emptyList() else brand.split(" ").filter { it.isNotBlank() && !it.first().isDigit() }
        val relation = when (slots.firstOrNull()) {
            null -> MealRelation.ANY
            WAKE -> MealRelation.ON_WAKE
            BEFORE_BREAKFAST, BEFORE_LUNCH, BEFORE_DINNER -> MealRelation.BEFORE
            BEDTIME -> MealRelation.AT_BEDTIME
            else -> MealRelation.AFTER
        }
        val amount = if (dose == "半顆") 0.5 else 1.0
        return CatalogEntryDto(
            catalogId = id, displayName = name, genericName = generic, brandName = brand.takeIf { it != "各廠牌" },
            category = cat, aliases = (listOf(generic) + brandTokens).distinct(),
            defaultDose = DoseDto(amount, "顆", dose), defaultSlots = slots, mealRelation = relation, weeklyOn = weekly,
        )
    }

    val items: List<CatalogEntryDto> = listOf(
        med("M01", "降血壓藥", listOf(AFTER_BREAKFAST), "1 顆", "Amlodipine", "高血壓", "Norvasc 脈優"),
        med("M02", "降血壓藥", listOf(AFTER_BREAKFAST), "1 顆", "Losartan", "高血壓", "Cozaar 可悅您"),
        med("M03", "降血壓藥", listOf(AFTER_BREAKFAST), "1 顆", "Valsartan", "高血壓", "Diovan 得安穩"),
        med("M04", "降血壓藥", listOf(AFTER_BREAKFAST), "1 顆", "Nifedipine", "高血壓", "Adalat OROS 冠達悅"),
        med("M05", "降血脂藥", listOf(BEDTIME), "1 顆", "Atorvastatin", "高血脂", "Lipitor 立普妥"),
        med("M06", "降血脂藥", listOf(BEDTIME), "1 顆", "Rosuvastatin", "高血脂", "Crestor 冠脂妥"),
        med("M07", "降血糖藥", listOf(AFTER_BREAKFAST, AFTER_DINNER), "1 顆", "Metformin", "糖尿病", "Glucophage 庫魯化"),
        med("M08", "降血糖藥", listOf(BEFORE_BREAKFAST), "1 顆", "Glimepiride", "糖尿病", "Amaryl 瑪爾胰"),
        med("M09", "降血糖藥", listOf(AFTER_BREAKFAST), "1 顆", "Sitagliptin", "糖尿病", "Januvia 佳糖維"),
        med("M10", "降血糖藥", listOf(AFTER_BREAKFAST), "1 顆", "Linagliptin", "糖尿病", "Trajenta 糖漸平"),
        med("M11", "心腎保護藥", listOf(AFTER_BREAKFAST), "1 顆", "Empagliflozin", "心衰竭", "Jardiance 恩排糖"),
        med("M12", "心腎保護藥", listOf(AFTER_BREAKFAST), "1 顆", "Dapagliflozin", "心衰竭", "Forxiga 福適佳"),
        med("M13", "利尿劑", listOf(AFTER_BREAKFAST, AFTER_DINNER), "1 顆", "Furosemide", "心衰竭", "Lasix 來適泄"),
        med("M14", "利尿劑", listOf(AFTER_BREAKFAST), "1 顆", "Spironolactone", "心衰竭", "Aldactone 安達通"),
        med("M15", "心臟藥", listOf(AFTER_BREAKFAST, AFTER_DINNER), "1 顆", "Sacubitril/Valsartan", "心衰竭", "Entresto 健安心"),
        med("M16", "心臟藥", listOf(AFTER_BREAKFAST), "1 顆", "Bisoprolol", "心衰竭", "Concor 康肯"),
        med("M17", "心臟藥", listOf(AFTER_BREAKFAST, AFTER_DINNER), "1 顆", "Carvedilol", "心衰竭", "Dilatrend 達利全"),
        med("M18", "強心藥", listOf(BEDTIME), "半顆", "Digoxin", "心衰竭", "Lanoxin 隆我心"),
        med("M19", "阿斯匹靈", listOf(AFTER_BREAKFAST), "1 顆", "Aspirin", "抗血栓", "Aspirin 100mg"),
        med("M20", "抗血栓藥", listOf(AFTER_BREAKFAST), "1 顆", "Clopidogrel", "抗血栓", "Plavix 保栓通"),
        med("M21", "抗凝血藥", listOf(AFTER_DINNER), "1 顆", "Rivaroxaban", "抗血栓", "Xarelto 拜瑞妥"),
        med("M22", "抗凝血藥", listOf(AFTER_BREAKFAST, AFTER_DINNER), "1 顆", "Apixaban", "抗血栓", "Eliquis 艾必克凝"),
        med("M23", "抗凝血藥", listOf(AFTER_BREAKFAST), "1 顆", "Edoxaban", "抗血栓", "Lixiana 里先安"),
        med("M24", "抗凝血藥", listOf(AFTER_DINNER), "1 顆", "Warfarin", "抗血栓", "Coumadin"),
        med("M25", "舌下含片", listOf(), "1 顆", "Nitroglycerin", "心絞痛", "NTG 舌下錠"),
        med("M26", "軟便藥", listOf(BEDTIME), "1 顆", "Sennoside", "腸胃", "各廠牌"),
        med("M27", "軟便藥", listOf(BEDTIME), "1 顆", "Magnesium oxide", "腸胃", "各廠牌"),
        med("M28", "胃藥", listOf(BEFORE_BREAKFAST), "1 顆", "Esomeprazole", "腸胃", "Nexium 耐適恩"),
        med("M29", "胃藥", listOf(BEFORE_BREAKFAST), "1 顆", "Pantoprazole", "腸胃", "Pantoloc 保衛康治潰樂"),
        med("M30", "脹氣藥", listOf(AFTER_BREAKFAST, AFTER_LUNCH, AFTER_DINNER), "1 顆", "Dimethicone", "腸胃", "Gascon 加斯克兒"),
        med("M31", "腸胃蠕動藥", listOf(BEFORE_BREAKFAST, BEFORE_LUNCH, BEFORE_DINNER), "1 顆", "Mosapride", "腸胃", "Gasmotin 加斯清"),
        med("M32", "痛風藥", listOf(AFTER_BREAKFAST), "1 顆", "Allopurinol", "痛風", "各廠牌"),
        med("M33", "痛風藥", listOf(AFTER_BREAKFAST), "1 顆", "Febuxostat", "痛風", "Feburic 福避痛"),
        med("M34", "痛風藥", listOf(AFTER_BREAKFAST), "1 顆", "Colchicine", "痛風", "各廠牌"),
        med("M35", "攝護腺藥", listOf(BEDTIME), "1 顆", "Tamsulosin", "泌尿", "Harnalidge 活路利淨"),
        med("M36", "安眠藥", listOf(BEDTIME), "1 顆", "Zolpidem", "睡眠", "Stilnox 使蒂諾斯"),
        med("M37", "止痛藥", listOf(), "1 顆", "Acetaminophen", "止痛", "Panadol 普拿疼"),
        med("M38", "消炎止痛藥", listOf(AFTER_BREAKFAST), "1 顆", "Celecoxib", "止痛", "Celebrex 希樂葆"),
        med("M39", "骨鬆藥", listOf(WAKE), "1 顆", "Alendronate", "骨質疏鬆", "Fosamax 福善美", weekly = Weekday.MON),
        med("M40", "鈣片", listOf(AFTER_DINNER), "1 顆", "Calcium + Vit D", "骨質疏鬆", "各廠牌"),
        med("M41", "甲狀腺藥", listOf(WAKE), "1 顆", "Levothyroxine", "甲狀腺", "Eltroxin 昂特欣"),
    )
}
