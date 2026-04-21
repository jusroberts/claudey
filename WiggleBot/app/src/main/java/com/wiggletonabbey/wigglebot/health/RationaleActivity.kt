package com.wiggletonabbey.wigglebot.health

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wiggletonabbey.wigglebot.ui.WiggleBotTheme

class RationaleActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            WiggleBotTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            "Health Data Access",
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp,
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "WiggleBot reads your running history from Health Connect to infer " +
                            "which days you typically run, so it can send smart reminders only " +
                            "on days you'd expect to go out.",
                            fontSize = 14.sp,
                            lineHeight = 22.sp,
                        )
                        Spacer(Modifier.height(24.dp))
                        Button(onClick = { finish() }) {
                            Text("OK")
                        }
                    }
                }
            }
        }
    }
}
