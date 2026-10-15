package com.jasongzy.cleanupwatcher

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import java.text.DateFormat
import java.util.Date
import java.util.TimeZone

@Composable
internal fun formatTimestamp(time: Long, seconds: Boolean = true): String {
    val locale = LocalConfiguration.current.locales[0]
    val zone = TimeZone.getDefault()
    return remember(time, locale, zone.id, seconds) {
        DateFormat.getDateTimeInstance(
                DateFormat.SHORT,
                if (seconds) DateFormat.MEDIUM else DateFormat.SHORT,
                locale,
            )
            .apply { timeZone = zone }
            .format(Date(time))
    }
}
