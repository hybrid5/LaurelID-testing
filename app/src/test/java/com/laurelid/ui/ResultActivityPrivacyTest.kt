package com.laurelid.ui

import android.content.Context
import android.content.Intent
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.laurelid.R
import com.laurelid.auth.VerifierService
import com.laurelid.data.VerificationResult
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ResultActivityPrivacyTest {
    @Test
    fun `failure result hides issuer and uses reason code`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val intent = Intent(context, ResultActivity::class.java).apply {
            putExtra(
                ResultActivity.EXTRA_VERIFICATION_RESULT,
                VerificationResult(
                    success = false,
                    ageOver21 = null,
                    issuer = null,
                    subjectDid = null,
                    docType = "org.iso.18013.5.1.mDL",
                    error = VerifierService.ERROR_INVALID_SIGNATURE,
                    trustStale = null,
                ),
            )
        }

        val controller = Robolectric.buildActivity(ResultActivity::class.java, intent).setup()
        val activity = controller.get()

        val detail = activity.findViewById<TextView>(R.id.resultDetail)
        val expectedDetail = activity.getString(
            R.string.result_failure_detail,
            VerifierService.ERROR_INVALID_SIGNATURE,
        )
        assertEquals(expectedDetail, detail.text.toString())

        val issuer = activity.findViewById<TextView>(R.id.resultIssuer)
        val expectedIssuer = activity.getString(
            R.string.result_issuer,
            activity.getString(R.string.result_unknown_issuer),
        )
        assertEquals(expectedIssuer, issuer.text.toString())

        controller.pause().stop().destroy()
    }
}
