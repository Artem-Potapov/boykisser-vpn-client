package com.justme.xtls_core_proxy.subs

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.justme.xtls_core_proxy.R
import com.justme.xtls_core_proxy.i18n.LocalizedComponentActivity
import com.justme.xtls_core_proxy.ui.theme.BoykisserMagenta
import com.justme.xtls_core_proxy.ui.theme.XTLS_CORE_PROXYTheme

class BoykisserInfoActivity : LocalizedComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            XTLS_CORE_PROXYTheme {
                BoykisserInfoScreen(onBack = { finish() })
            }
        }
    }

    companion object {
        const val SITE_URL = "https://boykiss3r.site"
        const val BOT_URL = "https://t.me/boykisser_vpn_bot"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BoykisserInfoScreen(onBack: () -> Unit) {
    val context = LocalContext.current

    fun openUrl(url: String) {
        val opened = runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }.isSuccess
        if (!opened) {
            Toast.makeText(context, url, Toast.LENGTH_SHORT).show()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.boykisser_info_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.subs_cd_back)
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = stringResource(R.string.boykisser_info_placeholder),
                style = MaterialTheme.typography.headlineLarge,
                color = BoykisserMagenta,
                textAlign = TextAlign.Center
            )
            Button(
                onClick = { openUrl(BoykisserInfoActivity.SITE_URL) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = BoykisserMagenta,
                    contentColor = Color.White
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.boykisser_info_open_site))
            }
            Button(
                onClick = { openUrl(BoykisserInfoActivity.BOT_URL) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = BoykisserMagenta,
                    contentColor = Color.White
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.boykisser_info_open_bot))
            }
        }
    }
}
