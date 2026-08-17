package com.fabrice.plansms.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fabrice.plansms.ui.PlanSmsViewModel
import com.fabrice.plansms.ui.theme.NightBlue
import com.fabrice.plansms.ui.theme.Mint

/** Écran de verrouillage PIN (4 chiffres). */
@Composable
fun PinScreen(vm: PlanSmsViewModel) {
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }

    fun onDigit(d: String) {
        if (pin.length >= 4) return
        val newPin = pin + d
        pin = newPin
        if (newPin.length == 4) {
            val ok = vm.verifyPin(newPin)
            if (ok) pin = ""
            else {
                error = true
                pin = ""
            }
        }
    }

    Box(
        modifier = Modifier.fillMaxSize().background(NightBlue),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("🔒", fontSize = 48.sp)
            Spacer(Modifier.height(10.dp))
            Text("PlanSMS verrouillé", color = androidx.compose.ui.graphics.Color.White, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(16.dp))
            // Points du PIN
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                repeat(4) { i ->
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .background(
                                color = if (i < pin.length) Mint else androidx.compose.ui.graphics.Color.White.copy(alpha = 0.3f),
                                shape = RoundedCornerShape(50)
                            )
                    )
                }
            }
            if (error) {
                Spacer(Modifier.height(10.dp))
                Text("PIN incorrect", color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(26.dp))
            // Pavé numérique
            val digits = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9")
            digits.chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    row.forEach { d ->
                        PinKey(d) { onDigit(d) }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Spacer(Modifier.size(64.dp))
                PinKey("0") { onDigit("0") }
                Surface(
                    modifier = Modifier.size(64.dp).clickable { pin = "" },
                    shape = RoundedCornerShape(16.dp),
                    color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.12f)
                ) {
                    Box(contentAlignment = Alignment.Center) { Text("⌫", color = androidx.compose.ui.graphics.Color.White, fontSize = 20.sp) }
                }
            }
        }
    }
}

@Composable
private fun PinKey(digit: String, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.size(64.dp).clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.12f)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(digit, color = androidx.compose.ui.graphics.Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        }
    }
}
