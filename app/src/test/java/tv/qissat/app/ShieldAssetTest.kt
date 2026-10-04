package tv.qissat.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tv.qissat.app.web.Shield

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ShieldAssetTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test fun placeholdersAreReplaced() {
        val shield = Shield(context)
        assertEquals(32, shield.nonce.length)
        assertFalse(shield.script.contains("__QSH_"))
        assertTrue(shield.script.contains(shield.nonce))
        assertTrue(shield.script.contains(tv.qissat.app.Config.BRIDGE_HOST))
    }

    @Test fun eachProcessGetsItsOwnNonce() {
        assertFalse(Shield(context).nonce == Shield(context).nonce)
    }

    /** Old Fire TV WebViews (Chromium ~50) cannot parse ES2015+ syntax; one syntax error disables the whole shield. */
    @Test fun scriptIsPlainEs5() {
        val code = Shield(context).script.lines()
            .map { it.substringBefore("//").trim() } // line comments are harmless
            .filterNot { it.startsWith("*") || it.startsWith("/*") }
            .joinToString("\n")
        val es6 = Regex("""(^|[^\w.])(let|const)\s+\w|=>|\bclass\s+\w+\s*[{]|`|\.\.\.\w|\basync\s+function|\bawait\s""")
        val hit = es6.find(code)
        assertTrue("ES2015+ syntax in shield.js near: ${hit?.value}", hit == null)
    }
}
