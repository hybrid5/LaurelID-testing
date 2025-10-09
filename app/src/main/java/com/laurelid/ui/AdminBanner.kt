package com.laurelid.ui

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.view.isVisible
import com.laurelid.R
import com.laurelid.trust.TrustStatus
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class AdminBanner @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    private val titleView: TextView
    private val messageView: TextView
    private val detailView: TextView

    init {
        LayoutInflater.from(context).inflate(R.layout.view_admin_banner, this, true)
        titleView = findViewById(R.id.adminBannerTitle)
        messageView = findViewById(R.id.adminBannerMessage)
        detailView = findViewById(R.id.adminBannerDetail)
        isVisible = false
    }

    fun render(status: TrustStatus) {
        isVisible = status.degraded
        if (!status.degraded) {
            return
        }
        titleView.text = context.getString(R.string.trust_banner_title)
        messageView.text = context.getString(R.string.trust_banner_message)
        detailView.text = buildDetail(status)
    }

    private fun buildDetail(status: TrustStatus): CharSequence {
        val anchorText = context.resources.getQuantityString(
            R.plurals.trust_banner_anchor_count,
            status.anchors,
            status.anchors,
        )
        val updated = status.lastUpdated?.let { instant ->
            DATE_FORMATTER.format(instant.atZone(ZoneId.systemDefault()))
        }
        return if (updated != null) {
            context.getString(R.string.trust_banner_last_updated, anchorText, updated)
        } else {
            anchorText
        }
    }

    companion object {
        private val DATE_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("MMM d, yyyy HH:mm z", Locale.US)
    }
}
