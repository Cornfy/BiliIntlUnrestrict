package me.bili.unrestrict.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import me.bili.unrestrict.ui.theme.AppTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            AppTheme {
                var selectedTab by rememberSaveable { mutableIntStateOf(0) }

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    bottomBar = {
                        NavigationBar {
                            NavigationBarItem(
                                selected = selectedTab == 0,
                                onClick = { selectedTab = 0 },
                                icon = {
                                    Icon(
                                        imageVector = if (selectedTab == 0) Icons.Filled.ChatBubble else Icons.Outlined.ChatBubbleOutline,
                                        contentDescription = "发评反诈"
                                    )
                                },
                                label = { Text("发评反诈") }
                            )
                            NavigationBarItem(
                                selected = selectedTab == 1,
                                onClick = { selectedTab = 1 },
                                icon = {
                                    Icon(
                                        imageVector = if (selectedTab == 1) Icons.Filled.Tune else Icons.Outlined.Tune,
                                        contentDescription = "功能增强"
                                    )
                                },
                                label = { Text("功能增强") }
                            )
                        }
                    }
                ) { innerPadding ->
                    Surface(modifier = Modifier.padding(innerPadding)) {
                        if (selectedTab == 0) {
                            CommentFraudHistoryScreen()
                        } else {
                            EnhanceSettingsScreen()
                        }
                    }
                }
            }
        }
    }
}
