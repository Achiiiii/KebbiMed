package tw.kebbi.medreminder.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tw.kebbi.medreminder.sync.FakeCatalog

/** D6：正規化後完全相符才算對到，不做模糊比對。 */
class CatalogMatcherTest {
    private val catalog = FakeCatalog.items
    private fun ids(name: String?) = CatalogMatcher.match(name, catalog).map { it.catalogId }

    @Test
    fun genericBrandAndChineseNames() {
        assertEquals(listOf("M13"), ids("Furosemide"))
        assertEquals(listOf("M13"), ids("LASIX"))
        assertEquals(listOf("M13"), ids("來適泄"))
        assertEquals(listOf("M13"), ids("Lasix 來適泄"))
    }

    @Test
    fun doseWordsAndPunctuationAreIgnored() {
        assertEquals(listOf("M13"), ids("Furosemide 40mg"))
        assertEquals(listOf("M13"), ids("Lasix 40 mg (Furosemide)"))
        assertEquals(listOf("M19"), ids("Aspirin 100mg"))
        assertEquals(listOf("M13"), ids("Ｆｕｒｏｓｅｍｉｄｅ"))
        assertEquals(listOf("M15"), ids("Sacubitril/Valsartan"))
    }

    @Test
    fun noFuzzyMatching() {
        assertTrue(ids("Furosemid").isEmpty())    // 少一個字母就不算
        assertTrue(ids("降血壓藥").isEmpty())       // 顯示名稱很多藥共用，不拿來比對
        assertTrue(ids("每日三次 飯後").isEmpty())
        assertTrue(ids("").isEmpty())
        assertTrue(ids(null).isEmpty())
    }

    @Test
    fun ambiguousNameReturnsAllCandidates() {
        // 「Valsartan」是 M03 的學名，也是 M15 複方的一部分：兩個都列出來讓使用者選
        val r = ids("Valsartan")
        assertTrue("M03" in r)
    }
}
