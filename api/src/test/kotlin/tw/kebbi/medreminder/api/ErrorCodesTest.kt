package tw.kebbi.medreminder.api

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ErrorCodesTest {

    @Test
    fun `照片本身被拒收的 4xx 不重試`() {
        listOf(400, 404, 410, 415, 422).forEach { assertTrue("$it", ErrorCodes.isUploadRejected(it)) }
    }

    @Test
    fun `照片太大另外處理`() {
        assertFalse(ErrorCodes.isUploadRejected(413))
    }

    @Test
    fun `跟照片無關的錯誤之後再傳`() {
        listOf(401, 403, 408, 409, 429, 500, 502, 503, -1).forEach { assertFalse("$it", ErrorCodes.isUploadRejected(it)) }
    }

    @Test
    fun `成功不算拒收`() {
        listOf(200, 201, 304).forEach { assertFalse("$it", ErrorCodes.isUploadRejected(it)) }
    }
}
