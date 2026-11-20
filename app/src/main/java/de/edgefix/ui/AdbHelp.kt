package de.edgefix.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import de.edgefix.calib.Network

/**
 * Befehle für den Weg über einen PC mit adb. Zeigt die IP-Adresse des Fire TV
 * gleich mit an.
 */
@Composable
fun PcCommands(vararg commands: String, intro: String = "Mit einem PC im selben WLAN (adb installiert):") {
    val ip = remember { Network.localIp() ?: "<IP-des-Fire-TV>" }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(intro, fontSize = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(
            Modifier
                .fillMaxWidth()
                .background(Color(0xFF0B0D0F), RoundedCornerShape(10.dp))
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("adb connect $ip:5555", fontSize = 19.sp, fontFamily = FontFamily.Monospace)
            for (c in commands) Text("adb shell $c", fontSize = 19.sp, fontFamily = FontFamily.Monospace)
        }
    }
}
